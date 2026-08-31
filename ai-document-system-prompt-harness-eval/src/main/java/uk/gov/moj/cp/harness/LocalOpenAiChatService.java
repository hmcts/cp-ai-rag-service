package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.harness.HarnessEnv.env;
import static uk.gov.moj.cp.harness.HarnessEnv.intEnv;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.service.ChatService;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChatService} for locally hosted OpenAI-compatible models (LM Studio / llama.cpp
 * server), so open-source models can run in the same harness matrix as the Azure-hosted
 * baselines. Selected by the {@code local:} provider prefix in {@code HARNESS_LLM_DEPLOYMENTS},
 * which must carry an explicit endpoint, e.g.:
 * {@code local:gpt-oss-20b@http://localhost:1234/v1}.
 *
 * <p>Follows the shared {@code OpenAiChatService}'s request shape — the OpenAI Responses API
 * (LM Studio serves {@code /v1/responses}), {@code max_output_tokens} from
 * {@code LLM_MODEL_RESPONSE_MAX_TOKENS}, and {@code temperature=0}/{@code top_p=0} for
 * deterministic decoding, matching the gpt-4o baseline's constrained decoding. It differs only
 * where local serving demands it: a plain API key instead of Entra bearer tokens
 * ({@code HARNESS_LOCAL_LLM_API_KEY}, default {@code lm-studio} — LM Studio accepts any value),
 * and the endpoint used exactly as given (no Azure {@code /openai/v1} suffixing). Reasoning
 * controls are deliberately not sent: local reasoning models (e.g. gpt-oss) take their effort
 * from the server/chat-template configuration.
 *
 * <p>Harness-local by design, same containment rule as {@link AnthropicChatService}: no local
 *-serving concerns leak into the shared artefacts or function apps.
 */
public final class LocalOpenAiChatService implements ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalOpenAiChatService.class);

    /** One client per endpoint; harness runs are single-endpoint but cheap to cache correctly. */
    private static final ConcurrentHashMap<String, OpenAIClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    private static final double TEMPERATURE = 0.0;
    private static final double TOP_P = 0.0;

    private final OpenAIClient client;
    private final String model;
    private final int maxTokens;

    public LocalOpenAiChatService(final String model, final String endpoint) {
        if (isNullOrEmpty(endpoint)) {
            throw new IllegalStateException("local: entries in HARNESS_LLM_DEPLOYMENTS require an inline endpoint, "
                    + "e.g. local:gpt-oss-20b@http://localhost:1234/v1");
        }
        this.model = model;
        this.maxTokens = intEnv("LLM_MODEL_RESPONSE_MAX_TOKENS", 1000);
        this.client = CLIENT_CACHE.computeIfAbsent(endpoint, key -> {
            LOGGER.info("Creating local OpenAI-compatible client for {} (model {})", key, model);
            return OpenAIOkHttpClient.builder()
                    .baseUrl(key)
                    .apiKey(env("HARNESS_LOCAL_LLM_API_KEY", "lm-studio"))
                    .build();
        });
    }

    @Override
    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction,
                                     final Class<T> responseClass) throws ChatServiceException {
        if (responseClass != String.class) {
            throw new ChatServiceException("LocalOpenAiChatService supports String responses only, got "
                    + responseClass.getName());
        }

        final ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .instructions(systemInstruction)
                .input(userInstruction)
                .maxOutputTokens((long) maxTokens)
                .temperature(TEMPERATURE)
                .topP(TOP_P)
                .build();

        try {
            final Response response = client.responses().create(params);
            final String content = extractOutputText(response);
            final String status = response.status().map(ResponseStatus::toString).orElse("(no status)");

            if (isNullOrEmpty(content)) {
                throw new ChatServiceException("Local LLM produced an empty response. Response status: " + status);
            }
            if (response.incompleteDetails().isPresent()) {
                // Feeds the jsonBlockPresent metric downstream, same as the cloud services.
                LOGGER.warn("Local LLM produced an incomplete response (status {}): {}",
                        status, response.incompleteDetails().get());
            } else {
                LOGGER.info("Received response from local LLM. Status: {}", status);
            }
            return Optional.of(responseClass.cast(content));
        } catch (final ChatServiceException e) {
            throw e;
        } catch (final Exception e) {
            throw new ChatServiceException("Error calling local LLM at model " + model, e);
        }
    }

    private String extractOutputText(final Response response) {
        final StringBuilder text = new StringBuilder();
        for (final ResponseOutputItem item : response.output()) {
            item.message().ifPresent(message -> {
                for (final ResponseOutputMessage.Content content : message.content()) {
                    content.outputText().ifPresent(t -> text.append(t.text()));
                }
            });
        }
        return text.toString();
    }
}
