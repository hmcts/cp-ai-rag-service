package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getDefaultAzureCredentialBuilder;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.exception.ChatServiceException;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.ContentFilterResultsForChoice;
import com.azure.ai.openai.models.ContentFilterResultsForPrompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatService.class);

    private final OpenAIClient openAIClient;

    private static final int MAX_TOKENS = 4000;
    private static final double TEMPERATURE = 0.0;
    private static final double TOP_P = 0.0;

    private final String deploymentName;

    public ChatService(String endpoint, String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable must be set.");

        LOGGER.info("Connecting to chat service endpoint '{}' and deployment '{}'", endpoint, deploymentName);

        this.deploymentName = deploymentName;

        this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(getDefaultAzureCredentialBuilder())
                .buildClient();
        LOGGER.info("Initialized Azure OpenAI client for chat with Managed Identity.");
    }

    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction, Class<T> responseClass) throws ChatServiceException {
        final List<ChatRequestMessage> chatMessages = getChatMessages(systemInstruction, userInstruction);

        ChatCompletionsOptions chatCompletionsOptions = new ChatCompletionsOptions(chatMessages)
                .setMaxTokens(MAX_TOKENS) // Keep the response concise
                .setTemperature(TEMPERATURE) // Low temperature for deterministic scoring
                .setTopP(TOP_P);

        try {
            final ChatCompletions chatCompletions = openAIClient.getChatCompletions(deploymentName, chatCompletionsOptions);
            final ChatChoice chatChoice = chatCompletions.getChoices().get(0);
            final String jsonResponse = chatChoice.getMessage().getContent();
            final CompletionsFinishReason finishReason = chatChoice.getFinishReason();

            final String resultExplanation = generateExplanationForEmptyResponse(chatChoice, chatCompletions, finishReason);

            if (isNullOrEmpty(jsonResponse)) {
                throw new ChatServiceException("LLM produced an empty response.  See explanation below \n" + resultExplanation);
            } else {
                LOGGER.info("Received response from LLM. Finish reason: {}", finishReason);
                if (CompletionsFinishReason.CONTENT_FILTERED.equals(finishReason)) {
                    LOGGER.warn("LLM produced filtered response.  See details \n{}", resultExplanation);
                } else if (CompletionsFinishReason.TOKEN_LIMIT_REACHED.equals(finishReason)) {
                    LOGGER.warn("LLM produced incomplete response as token limit was reached.  See details \n{}", resultExplanation);
                } else {
                    LOGGER.info("LLM produced complete response.  See details \n{}", resultExplanation);
                }
            }

            final T responseModel;
            if (responseClass == String.class) {
                responseModel = responseClass.cast(jsonResponse);
            } else {
                responseModel = new ObjectMapper().readValue(jsonResponse, responseClass);
            }
            return Optional.of(responseModel);
        } catch (JsonProcessingException e) {
            throw new ChatServiceException("Error calling LLM for evaluation", e);
        }
    }

    private List<ChatRequestMessage> getChatMessages(final String systemInstruction, final String userInstruction) {
        return List.of(
                new ChatRequestSystemMessage(systemInstruction),
                new ChatRequestUserMessage(userInstruction)
        );
    }


    private static String generateExplanationForEmptyResponse(final ChatChoice chatChoice, final ChatCompletions chatCompletions, final CompletionsFinishReason finishReason) {
        final StringBuilder resultExplanation = new StringBuilder("Finish reason: ").append(finishReason);

        if (CompletionsFinishReason.CONTENT_FILTERED.equals(finishReason)) {
            try {
                for (ContentFilterResultsForPrompt promptResult : chatCompletions.getPromptFilterResults()) {
                    if (promptResult.getContentFilterResults() != null && LOGGER.isWarnEnabled()) {
                        final String promptContentFilterString = promptResult.getContentFilterResults().toJsonString();
                        resultExplanation.append("\n").append("--- PROMPT FILTERING STATUS (Input) ---\n").append(promptContentFilterString);
                    }
                }

                ContentFilterResultsForChoice completionResult = chatChoice.getContentFilterResults();
                if (completionResult != null && LOGGER.isWarnEnabled()) {
                    final String completionResultFilterString = completionResult.toJsonString();
                    resultExplanation.append("\n").append("--- RESPONSE FILTERING STATUS (Output) ---\n").append(completionResultFilterString);
                }

            } catch (final IOException e) {
                return resultExplanation.toString();
            }
        }

        return resultExplanation.toString();
    }

}
