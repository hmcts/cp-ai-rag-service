package uk.gov.moj.cp.ai.service;

import static java.lang.Integer.parseInt;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_MODEL_RESPONSE_MAX_TOKENS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.LLM_MODEL_RESPONSE_VERBOSITY;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAiClientFactory;
import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.util.EnvVarUtil;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.client.OpenAIClient;
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

    private final OpenAIClient openAIClient;
    private final String deploymentName;
    private final int maxTokens;
    private final String verbosity;

    public OpenAiChatService(final String endpoint, final String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable must be set.");

        this.deploymentName = deploymentName;
        this.openAIClient = OpenAiClientFactory.getInstance(endpoint);

        maxTokens = getRequiredEnvAsInteger(LLM_MODEL_RESPONSE_MAX_TOKENS, MAX_TOKENS);
        verbosity = getRequiredEnv(LLM_MODEL_RESPONSE_VERBOSITY, "low");
    }

    protected OpenAiChatService(final OpenAIClient openAIClient, final String deploymentName) {
        this.deploymentName = deploymentName;
        this.openAIClient = openAIClient;
        maxTokens = parseInt(MAX_TOKENS);
        verbosity = getRequiredEnv(LLM_MODEL_RESPONSE_VERBOSITY, "low");
        LOGGER.info("Returning initialized OpenAI client for chat.");
    }

    @Override
    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction, Class<T> responseClass) throws ChatServiceException {
        // GPT-5 / o-series reasoning models reject sampling parameters (temperature/top_p) other
        // than the default. Detect by deployment name and configure compatibly.
        final boolean isReasoningModel = isReasoningModel(deploymentName);

        final ResponseCreateParams.Builder paramsBuilder = ResponseCreateParams.builder()
                .model(deploymentName)
                .instructions(systemInstruction)
                .input(userInstruction)
                .maxOutputTokens((long) maxTokens);

        if (!isReasoningModel) {
            paramsBuilder.temperature(TEMPERATURE).topP(TOP_P);
        }

        // verbosity is honored on the Responses API for GPT-5 reasoning models; silently ignored
        // by other models. Only attach the text config when explicitly configured via env var so
        // requests against non-GPT-5 deployments stay unchanged.
        if (verbosity != null) {
            paramsBuilder.text(ResponseTextConfig.builder()
                    .verbosity(ResponseTextConfig.Verbosity.of(verbosity))
                    .build());
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
        } catch (JsonProcessingException e) {
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

    private boolean isReasoningModel(final String deploymentName) {
        if (deploymentName == null) {
            return false;
        }
        final String d = deploymentName.toLowerCase();
        return d.startsWith("gpt-5") || d.startsWith("gpt5")
                || d.startsWith("o1") || d.startsWith("o3") || d.startsWith("o4");
    }

    private String ensureRawJsonAsConvertingPayloadToObject(final String llmResponse) {
        if (llmResponse.contains("```")) {
            LOGGER.info("LLM response contains \"```\" and will require sanitising");
        }

        int firstBrace = llmResponse.indexOf("{");
        int lastBrace = llmResponse.lastIndexOf("}");

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return llmResponse.substring(firstBrace, lastBrace + 1);
        }
        return llmResponse;
    }
}
