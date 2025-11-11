package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.List;
import java.util.Optional;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.identity.DefaultAzureCredentialBuilder;
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

        this.deploymentName = deploymentName;

        this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        LOGGER.info("Initialized Azure OpenAI client with Managed Identity.");
    }

    public <T> Optional<T> callModel(final String systemInstruction, final String userInstruction, Class<T> responseClass) {
        final List<ChatMessage> chatMessages = getChatMessages(systemInstruction, userInstruction);

        ChatCompletionsOptions chatCompletionsOptions = new ChatCompletionsOptions(chatMessages)
                .setMaxTokens(MAX_TOKENS) // Keep the response concise
                .setTemperature(TEMPERATURE) // Low temperature for deterministic scoring
                .setTopP(TOP_P)
                .setStream(false);

        try {

            final ChatCompletions chatCompletions = openAIClient.getChatCompletions(deploymentName, chatCompletionsOptions);
            final ChatChoice chatChoice = chatCompletions.getChoices().get(0);
            String jsonResponse = chatChoice.getMessage().getContent();

            if (isNullOrEmpty(jsonResponse)) {
                String finishReason = chatChoice.getFinishReason().toString();
                LOGGER.error("Received empty response from LLM. Finish reason: {}", finishReason);
                return Optional.empty();
            }

            final T responseModel;
            if (responseClass == String.class) {
                responseModel = responseClass.cast(jsonResponse);
            } else {
                responseModel = new ObjectMapper().readValue(jsonResponse, responseClass);
            }
            return Optional.of(responseModel);
        } catch (Exception e) {
            LOGGER.error("Error calling LLM for evaluation", e);
        }
        return Optional.empty();
    }

    private List<ChatMessage> getChatMessages(final String systemInstruction, final String userInstruction) {
        return List.of(
                new ChatMessage(ChatRole.SYSTEM).setContent(systemInstruction),
                new ChatMessage(ChatRole.USER).setContent(userInstruction)
        );
    }

}
