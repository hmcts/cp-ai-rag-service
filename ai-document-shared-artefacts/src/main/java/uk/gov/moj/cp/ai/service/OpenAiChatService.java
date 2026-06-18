package uk.gov.moj.cp.ai.service;

import static java.lang.Integer.parseInt;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_MODEL_RESPONSE_MAX_TOKENS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_MODEL_RESPONSE_VERBOSITY;
import static uk.gov.moj.cp.ai.util.ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject;
import static uk.gov.moj.cp.ai.util.ChatModelUtil.isReasoningModel;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAiClientFactory;
import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAiChatService implements ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiChatService.class);

    private static final String MAX_TOKENS = "1000";
    private static final double TEMPERATURE = 0.0;
    private static final double TOP_P = 0.0;

    /**
     * Output verbosity for GPT-5 reasoning models, applied via the Responses API
     * {@code text.verbosity}. Configurable via the {@code LLM_MODEL_RESPONSE_VERBOSITY} env var;
     * allowed values: {@code low | medium | high} (default {@link #DEFAULT_VERBOSITY}). Ignored by
     * non-reasoning models.
     */
    private static final String DEFAULT_VERBOSITY = "low";

    /**
     * Reasoning effort for reasoning models (gpt-5/o-series), applied via the Responses API
     * {@code reasoning.effort}. Configurable via the {@code LLM_REASONING_EFFORT} env var; allowed
     * values: {@code none | minimal | low | medium | high | xhigh} (default
     * {@link #DEFAULT_REASONING_EFFORT}). Lower effort frees output-token budget (less truncation of
     * the answer/JSON) and tends to yield more concise output. Ignored for non-reasoning models
     * (e.g. gpt-4o). Mirrors the same control in {@link AzureChatService}.
     */
    private static final String LLM_REASONING_EFFORT = "LLM_REASONING_EFFORT";
    private static final String DEFAULT_REASONING_EFFORT = "none";

    private final OpenAIClient openAIClient;
    private final String deploymentName;
    private final int maxTokens;
    private final String verbosity;
    private final String reasoningEffort;

    public OpenAiChatService(final String endpoint, final String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable must be set.");

        this.deploymentName = deploymentName;
        this.openAIClient = OpenAiClientFactory.getInstance(endpoint);

        maxTokens = getRequiredEnvAsInteger(LLM_MODEL_RESPONSE_MAX_TOKENS, MAX_TOKENS);
        verbosity = getRequiredEnv(LLM_MODEL_RESPONSE_VERBOSITY, DEFAULT_VERBOSITY);
        reasoningEffort = getRequiredEnv(LLM_REASONING_EFFORT, DEFAULT_REASONING_EFFORT);
    }

    protected OpenAiChatService(final OpenAIClient openAIClient, final String deploymentName) {
        this.deploymentName = deploymentName;
        this.openAIClient = openAIClient;
        maxTokens = parseInt(MAX_TOKENS);
        verbosity = getRequiredEnv(LLM_MODEL_RESPONSE_VERBOSITY, DEFAULT_VERBOSITY);
        reasoningEffort = getRequiredEnv(LLM_REASONING_EFFORT, DEFAULT_REASONING_EFFORT);
        LOGGER.info("Returning initialized OpenAI client for chat.");
    }

    @Override
    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction, Class<T> responseClass) throws ChatServiceException {
        // GPT-5 / o-series reasoning models reject sampling parameters (temperature/top_p) other
        // than the default. Detect by deployment name and configure compatibly.
        final boolean reasoningModel = isReasoningModel(deploymentName);

        final ResponseCreateParams.Builder paramsBuilder = ResponseCreateParams.builder()
                .model(deploymentName)
                .instructions(systemInstruction)
                .input(userInstruction)
                .maxOutputTokens((long) maxTokens);

        if (reasoningModel) {
            paramsBuilder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(reasoningEffort.trim().toLowerCase()))
                    .build());
            // verbosity is a GPT-5 reasoning-model Responses-API control. Non-reasoning models
            // (e.g. gpt-4o) REJECT it — `gpt-4o` returns 400 "Unsupported value: 'low' ... Supported
            // values are: 'medium'" — so only set it for reasoning models, alongside reasoning_effort.
            paramsBuilder.text(ResponseTextConfig.builder()
                    .verbosity(ResponseTextConfig.Verbosity.of(verbosity))
                    .build());
            LOGGER.info("Applied reasoning_effort='{}', verbosity='{}' for reasoning model '{}'",
                    reasoningEffort, verbosity, deploymentName);
        } else {
            paramsBuilder.temperature(TEMPERATURE).topP(TOP_P);
        }

        try {
            final Response response = openAIClient.responses().create(paramsBuilder.build());
            final String content = extractOutputText(response);
            final String status = response.status().map(ResponseStatus::toString).orElse("(no status)");
            final String resultExplanation = "Response status: " + status;

            if (isNullOrEmpty(content)) {
                throw new ChatServiceException("LLM produced an empty response.  See explanation below \n" + resultExplanation);
            }

            LOGGER.info("Received response from LLM. Status: {}", status);
            if (response.incompleteDetails().isPresent()) {
                LOGGER.warn("LLM produced incomplete response.  See details \n{}", resultExplanation);
            } else {
                LOGGER.info("LLM produced complete response.  See details \n{}", resultExplanation);
            }

            final T responseModel;
            if (responseClass == String.class) {
                responseModel = responseClass.cast(content);
            } else {
                final String sanitisedResponse = ensureRawJsonAsConvertingPayloadToObject(content);
                responseModel = getObjectMapper().readValue(sanitisedResponse, responseClass);
            }
            return Optional.ofNullable(responseModel);
        } catch (final JsonProcessingException e) {
            throw new ChatServiceException("Error calling LLM for evaluation", e);
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
