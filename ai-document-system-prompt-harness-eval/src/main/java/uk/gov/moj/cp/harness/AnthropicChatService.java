package uk.gov.moj.cp.harness;

import static java.util.stream.Collectors.joining;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.TokenUsage;
import uk.gov.moj.cp.ai.service.TokenUsageReporting;
import uk.gov.moj.cp.ai.util.CredentialUtil;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.foundry.backends.FoundryBackend;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Usage;
import com.azure.identity.AuthenticationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;

/**
 * Harness-local {@link ChatService} for Anthropic Claude models hosted on Azure AI Foundry or
 * Amazon Bedrock (see {@link Platform}).
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
 *       itself is an OpenAI reasoning-model knob and is ignored on this path.
 *       {@code disabled} sends an explicit thinking-off setting, for models that think by
 *       default when the field is omitted (e.g. Claude Sonnet 5).</li>
 *   <li>{@code HARNESS_ANTHROPIC_EFFORT} — optional {@code low|medium|high|xhigh|max}, sent as
 *       {@code output_config.effort}. Default: not sent (the model's own default). With thinking
 *       disabled it still steers output length and token spend.</li>
 * </ul>
 *
 * <p><b>Bedrock configuration</b> ({@link #forBedrock}):
 * <ul>
 *   <li>{@code AWS_BEARER_TOKEN_BEDROCK} — required; the Bedrock API key (sent as a bearer token,
 *       no SigV4 credentials needed).</li>
 *   <li>{@code HARNESS_BEDROCK_REGION} — optional, default {@code eu-west-2}.</li>
 *   <li>The model must be a base model ID ({@code anthropic.*}), never a cross-region inference
 *       profile ({@code eu.}/{@code global.}/...): a base ID is served in the configured region
 *       only, so requests stay in that region.</li>
 * </ul>
 */
public class AnthropicChatService implements ChatService, TokenUsageReporting {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicChatService.class);

    private static final String ENV_API_KEY = "ANTHROPIC_FOUNDRY_API_KEY";
    private static final String ENV_RESOURCE = "ANTHROPIC_FOUNDRY_RESOURCE";
    private static final String ENV_BASE_URL = "ANTHROPIC_FOUNDRY_BASE_URL";
    private static final String ENV_TEMPERATURE = "HARNESS_ANTHROPIC_TEMPERATURE";
    private static final String ENV_THINKING = "HARNESS_ANTHROPIC_THINKING";
    private static final String ENV_EFFORT = "HARNESS_ANTHROPIC_EFFORT";
    private static final String ENV_BEDROCK_API_KEY = "AWS_BEARER_TOKEN_BEDROCK";
    private static final String ENV_BEDROCK_REGION = "HARNESS_BEDROCK_REGION";
    private static final String DEFAULT_BEDROCK_REGION = "eu-west-2";
    private static final String BEDROCK_BASE_MODEL_PREFIX = "anthropic.";

    /** Where the Claude model is hosted; selects the SDK backend (routing + auth). */
    enum Platform { FOUNDRY, BEDROCK }

    /** Entra scope for Azure AI services (Foundry) — same scope the OpenAI-provider client uses. */
    private static final String AZURE_COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default";

    private static final int DEFAULT_MAX_TOKENS = 1000;

    /** Marker key for the client built from the ANTHROPIC_FOUNDRY_* environment variables. */
    private static final String ENV_DEFAULT_CLIENT_KEY = "<env-default>";

    /**
     * One client per platform + endpoint — models can live on different Foundry resources (per-model
     * {@code @endpoint} suffixes in HARNESS_LLM_DEPLOYMENTS); credentials are global.
     */
    private static final ConcurrentHashMap<String, AnthropicClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    private final String model;
    private final Platform platform;
    private final AnthropicClient client;
    private final long maxTokens;

    /** Optional per-call usage listener (see {@link TokenUsageReporting}); no-op when unset. */
    private volatile Consumer<TokenUsage> tokenUsageListener;

    /**
     * @param model           the Foundry deployment name (sent as the Messages API {@code model})
     * @param endpointOverride per-model base URL (must include the {@code /anthropic} path), or
     *                         empty to use the ANTHROPIC_FOUNDRY_* environment variables
     */
    public AnthropicChatService(final String model, final String endpointOverride) {
        this(model, Platform.FOUNDRY, endpointOverride);
    }

    /**
     * Claude on Amazon Bedrock, served in-region ({@code HARNESS_BEDROCK_REGION}, default
     * {@code eu-west-2}).
     *
     * @param model a Bedrock base model ID, e.g. {@code anthropic.claude-sonnet-4-6}
     */
    public static AnthropicChatService forBedrock(final String model) {
        requireInRegionBedrockModel(model);
        return new AnthropicChatService(model, Platform.BEDROCK, "");
    }

    private AnthropicChatService(final String model, final Platform platform, final String endpointOverride) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Anthropic model/deployment name must be set.");
        }
        this.model = model;
        this.platform = platform;
        final String endpointKey = (endpointOverride == null || endpointOverride.isBlank())
                ? ENV_DEFAULT_CLIENT_KEY : endpointOverride;
        this.client = CLIENT_CACHE.computeIfAbsent(platform + "|" + endpointKey,
                k -> platform == Platform.BEDROCK ? buildBedrockClient() : buildClient(endpointKey));
        this.maxTokens = HarnessEnv.intEnv("LLM_MODEL_RESPONSE_MAX_TOKENS", DEFAULT_MAX_TOKENS);
    }

    /**
     * Rejects cross-region inference profiles ({@code eu.}, {@code global.}, ...), which route
     * requests outside the configured region; only base model IDs stay in-region.
     */
    static void requireInRegionBedrockModel(final String model) {
        if (model == null || !model.startsWith(BEDROCK_BASE_MODEL_PREFIX)) {
            throw new IllegalArgumentException("Bedrock model '" + model + "' must be a base model ID starting with '"
                    + BEDROCK_BASE_MODEL_PREFIX + "' (e.g. anthropic.claude-sonnet-4-6). Cross-region inference"
                    + " profiles such as eu./global. route requests outside " + ENV_BEDROCK_REGION + ".");
        }
    }

    private static AnthropicClient buildBedrockClient() {
        final String apiKey = HarnessEnv.env(ENV_BEDROCK_API_KEY, "");
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(ENV_BEDROCK_API_KEY + " must be set (the Bedrock API key) to reach"
                    + " Claude on Amazon Bedrock.");
        }
        final String region = HarnessEnv.env(ENV_BEDROCK_REGION, DEFAULT_BEDROCK_REGION);
        LOGGER.info("[anthropic] using Amazon Bedrock in region {} with an API key ({})", region, ENV_BEDROCK_API_KEY);
        return AnthropicOkHttpClient.builder()
                .backend(BedrockBackend.builder()
                        .apiKey(apiKey)
                        .region(Region.of(region))
                        .build())
                .build();
    }

    private static AnthropicClient buildClient(final String endpointKey) {
        final FoundryBackend.Builder backend = FoundryBackend.builder();

        if (!ENV_DEFAULT_CLIENT_KEY.equals(endpointKey)) {
            LOGGER.info("[anthropic] using per-model endpoint {}", endpointKey);
            backend.baseUrl(endpointKey);
        } else {
            final String baseUrl = HarnessEnv.env(ENV_BASE_URL, "");
            final String resource = HarnessEnv.env(ENV_RESOURCE, "");
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

        final String apiKey = HarnessEnv.env(ENV_API_KEY, "");
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
        final String temperature = HarnessEnv.env(ENV_TEMPERATURE, "");
        if (!temperature.isEmpty()) {
            params.temperature(Double.parseDouble(temperature));
            LOGGER.info("[anthropic] applied temperature={} for model '{}'", temperature, model);
        }

        final String thinking = HarnessEnv.env(ENV_THINKING, "").trim().toLowerCase();
        if ("adaptive".equals(thinking)) {
            params.thinking(ThinkingConfigAdaptive.builder().build());
            LOGGER.info("[anthropic] adaptive thinking enabled for model '{}' — thinking shares the max_tokens budget ({})",
                    model, maxTokens);
        } else if ("disabled".equals(thinking)) {
            params.thinking(ThinkingConfigDisabled.builder().build());
            LOGGER.info("[anthropic] thinking explicitly disabled for model '{}'", model);
        }

        final String effort = HarnessEnv.env(ENV_EFFORT, "").trim().toLowerCase();
        if (!effort.isEmpty()) {
            params.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build());
            LOGGER.info("[anthropic] applied effort={} for model '{}'", effort, model);
        }

        try {
            final Message message = client.messages().create(params.build());
            reportTokenUsage(message.usage());
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
            throw new ChatServiceException("Anthropic " + platform + " call failed with HTTP " + e.statusCode()
                    + " for model '" + model + "'", e);
        } catch (final AnthropicException e) {
            throw new ChatServiceException("Anthropic " + platform + " call failed for model '" + model + "'", e);
        }
    }

    @Override
    public void setTokenUsageListener(final Consumer<TokenUsage> listener) {
        this.tokenUsageListener = listener;
    }

    private void reportTokenUsage(final Usage usage) {
        final TokenUsage tokenUsage = new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.outputTokensDetails().map(OutputTokensDetails::thinkingTokens).orElse(0L),
                usage.cacheReadInputTokens().orElse(0L));
        LOGGER.info("[anthropic] token usage for model '{}': input={} output={} (thinking={}) cachedInput={}",
                model, tokenUsage.inputTokens(), tokenUsage.outputTokens(),
                tokenUsage.reasoningTokens(), tokenUsage.cachedInputTokens());
        final Consumer<TokenUsage> listener = tokenUsageListener;
        if (listener != null) {
            listener.accept(tokenUsage);
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
