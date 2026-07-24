package uk.gov.moj.cp.harness;

import static java.util.stream.Collectors.joining;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.CredentialUtil;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.foundry.backends.FoundryBackend;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.azure.identity.AuthenticationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harness-local {@link ChatService} for Anthropic Claude models hosted on Azure AI Foundry.
 *
 * <p>Claude on Azure is served via the Anthropic Messages API — not Azure OpenAI chat
 * completions — so the production {@code AzureChatService}/{@code OpenAiChatService} cannot
 * reach it. This implementation is deliberately confined to the evaluation harness: the
 * Anthropic SDK is a harness-only dependency and nothing in the function apps or shared
 * artefacts references it.
 *
 * <p><b>Configuration</b> (read from the environment, exported by {@code run-harness.sh}):
 * <ul>
 *   <li>Endpoint — a per-model {@code @https://...} suffix on the model's
 *       {@code HARNESS_LLM_DEPLOYMENTS} entry takes precedence; otherwise
 *       {@code ANTHROPIC_FOUNDRY_RESOURCE} or {@code ANTHROPIC_FOUNDRY_BASE_URL} — exactly one.
 *       The resource form expands to {@code https://<resource>.services.ai.azure.com/anthropic};
 *       full URLs must include the {@code /anthropic} path segment.</li>
 *   <li>{@code ANTHROPIC_FOUNDRY_API_KEY} — optional. When unset, authentication falls back to a
 *       bearer token from {@code DefaultAzureCredential} (the same managed-identity/az-login chain
 *       every other client in this repo uses).</li>
 *   <li>{@code LLM_MODEL_RESPONSE_MAX_TOKENS} — sent as the (mandatory) Anthropic
 *       {@code max_tokens}. Same truncation semantics the gpt-5.1 evaluation dealt with: the
 *       budget must cover the full answer plus the {@code <FACT_MAP_JSON>} block.</li>
 *   <li>{@code HARNESS_ANTHROPIC_TEMPERATURE} — optional single sampling parameter. Claude 4.x
 *       rejects {@code temperature} and {@code top_p} together, so the production
 *       {@code temperature=0 + top_p=0} pair is never sent; by default both are omitted.</li>
 *   <li>{@code HARNESS_ANTHROPIC_THINKING=adaptive} — optional. Default is thinking off
 *       (the Sonnet 4.6 behaviour when the field is unset), mirroring the
 *       {@code LLM_REASONING_EFFORT=none} finding: extract-and-cite needs no deep reasoning and
 *       thinking tokens would share the {@code max_tokens} budget. {@code LLM_REASONING_EFFORT}
 *       itself is an OpenAI reasoning-model knob and is ignored on this path.</li>
 * </ul>
 */
public class AnthropicChatService implements ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicChatService.class);

    private static final String ENV_API_KEY = "ANTHROPIC_FOUNDRY_API_KEY";
    private static final String ENV_RESOURCE = "ANTHROPIC_FOUNDRY_RESOURCE";
    private static final String ENV_BASE_URL = "ANTHROPIC_FOUNDRY_BASE_URL";
    private static final String ENV_TEMPERATURE = "HARNESS_ANTHROPIC_TEMPERATURE";
    private static final String ENV_THINKING = "HARNESS_ANTHROPIC_THINKING";

    /** Entra scope for Azure AI services (Foundry) — same scope the OpenAI-provider client uses. */
    private static final String AZURE_COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default";

    private static final String DEFAULT_MAX_TOKENS = "1000";

    /** Marker key for the client built from the ANTHROPIC_FOUNDRY_* environment variables. */
    private static final String ENV_DEFAULT_CLIENT_KEY = "<env-default>";

    /**
     * One client per endpoint — models can live on different Foundry resources (per-model
     * {@code @endpoint} suffixes in HARNESS_LLM_DEPLOYMENTS); credentials are global.
     */
    private static final ConcurrentHashMap<String, AnthropicClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    private final String model;
    private final AnthropicClient client;
    private final long maxTokens;

    /**
     * @param model           the Foundry deployment name (sent as the Messages API {@code model})
     * @param endpointOverride per-model base URL (must include the {@code /anthropic} path), or
     *                         empty to use the ANTHROPIC_FOUNDRY_* environment variables
     */
    public AnthropicChatService(final String model, final String endpointOverride) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Anthropic model/deployment name must be set.");
        }
        this.model = model;
        final String key = (endpointOverride == null || endpointOverride.isBlank())
                ? ENV_DEFAULT_CLIENT_KEY : endpointOverride;
        this.client = CLIENT_CACHE.computeIfAbsent(key, AnthropicChatService::buildClient);
        this.maxTokens = TestHarness.intEnv("LLM_MODEL_RESPONSE_MAX_TOKENS", Integer.parseInt(DEFAULT_MAX_TOKENS));
    }

    private static AnthropicClient buildClient(final String endpointKey) {
        final FoundryBackend.Builder backend = FoundryBackend.builder();

        if (!ENV_DEFAULT_CLIENT_KEY.equals(endpointKey)) {
            LOGGER.info("[anthropic] using per-model endpoint {}", endpointKey);
            backend.baseUrl(endpointKey);
        } else {
            final String baseUrl = TestHarness.env(ENV_BASE_URL, "");
            final String resource = TestHarness.env(ENV_RESOURCE, "");
            if (!baseUrl.isEmpty()) {
                backend.baseUrl(baseUrl);
            } else if (!resource.isEmpty()) {
                backend.resource(resource);
            } else {
                throw new IllegalStateException("One of " + ENV_RESOURCE + " or " + ENV_BASE_URL
                        + " must be set (or give the model an @endpoint suffix in HARNESS_LLM_DEPLOYMENTS)"
                        + " to reach the Azure AI Foundry resource hosting the Claude deployment.");
            }
        }

        final String apiKey = TestHarness.env(ENV_API_KEY, "");
        if (!apiKey.isEmpty()) {
            LOGGER.info("[anthropic] authenticating to Foundry with an API key ({})", ENV_API_KEY);
            backend.apiKey(apiKey);
        } else {
            LOGGER.info("[anthropic] no {} set — authenticating to Foundry with a DefaultAzureCredential bearer token", ENV_API_KEY);
            backend.bearerTokenSupplier(AuthenticationUtil.getBearerTokenSupplier(
                    CredentialUtil.getCredentialInstance(), AZURE_COGNITIVE_SCOPE));
        }

        return AnthropicOkHttpClient.builder()
                .backend(backend.build())
                .build();
    }

    @Override
    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction,
                                     final Class<T> responseClass) throws ChatServiceException {
        if (responseClass != String.class) {
            // The harness only ever requests the raw String; keep this implementation honest
            // about its scope rather than half-supporting JSON deserialisation.
            throw new ChatServiceException("AnthropicChatService supports String responses only, requested: "
                    + responseClass.getName());
        }

        final MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemInstruction)
                .addUserMessage(userInstruction);

        // Claude 4.x rejects temperature and top_p together — never replicate the production
        // temperature=0 + top_p=0 pair here. Default: omit sampling entirely.
        final String temperature = TestHarness.env(ENV_TEMPERATURE, "");
        if (!temperature.isEmpty()) {
            params.temperature(Double.parseDouble(temperature));
            LOGGER.info("[anthropic] applied temperature={} for model '{}'", temperature, model);
        }

        final String thinking = TestHarness.env(ENV_THINKING, "").trim().toLowerCase();
        if ("adaptive".equals(thinking)) {
            params.thinking(ThinkingConfigAdaptive.builder().build());
            LOGGER.info("[anthropic] adaptive thinking enabled for model '{}' — thinking shares the max_tokens budget ({})",
                    model, maxTokens);
        }

        try {
            final Message message = client.messages().create(params.build());
            final String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(joining());

            logStopReason(message);

            if (text.isBlank()) {
                throw new ChatServiceException("LLM produced an empty response. Stop reason: "
                        + message.stopReason().map(StopReason::toString).orElse("(none)"));
            }
            return Optional.of(responseClass.cast(text));
        } catch (final AnthropicServiceException e) {
            throw new ChatServiceException("Anthropic Foundry call failed with HTTP " + e.statusCode()
                    + " for model '" + model + "'", e);
        } catch (final AnthropicException e) {
            throw new ChatServiceException("Anthropic Foundry call failed for model '" + model + "'", e);
        }
    }

    private void logStopReason(final Message message) {
        final StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            // The analogue of finish_reason=length: the answer (and likely the <FACT_MAP_JSON>
            // block) was cut off — surfaces in the harness as jsonBlockPresent=false.
            LOGGER.warn("[anthropic] response truncated at max_tokens={} for model '{}' — citation block may be incomplete",
                    maxTokens, model);
        } else if (StopReason.REFUSAL.equals(stopReason)) {
            LOGGER.warn("[anthropic] model '{}' returned stop_reason=refusal — content may be empty or partial", model);
        } else {
            LOGGER.info("[anthropic] received response from model '{}'. Stop reason: {}", model, stopReason);
        }
    }
}
