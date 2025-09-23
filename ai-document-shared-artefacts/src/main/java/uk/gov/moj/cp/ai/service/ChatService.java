package uk.gov.moj.cp.ai.service;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ChatService {

    private final Logger logger = Logger.getLogger(ChatService.class.getName());

    private final OpenAIClient openAIClient;

    private static final int MAX_TOKENS = 200;
    private static final double TEMPERATURE = 0.0;
    private static final double TOP_P = 0.0;

    private final String deploymentName;

    public ChatService(String endpoint, String apiKey, String deploymentName) {

        isNullOrEmpty(endpoint, "Endpoint environment variable must be set.");
        isNullOrEmpty(deploymentName, "Deployment name environment variable must be set.");

        this.deploymentName = deploymentName;

        // Choose authentication method: API Key or Managed Identity
        if (apiKey != null && !apiKey.isEmpty()) {
            // Option 1: API Key Authentication (simpler for dev, less secure for prod)
            this.openAIClient = new OpenAIClientBuilder()
                    .endpoint(endpoint)
                    .credential(new AzureKeyCredential(apiKey))
                    .buildClient();


            logger.info("Initialized Azure OpenAI client with API Key.");
        } else {
            // Option 2: Azure Managed Identity (Recommended for production)
            // Ensure your Azure App Service/Function App has a Managed Identity enabled
            // and granted 'Cognitive Services OpenAI User' role on your Azure OpenAI resource.
            this.openAIClient = new OpenAIClientBuilder()
                    .endpoint(endpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            logger.info("Initialized Azure OpenAI client with Managed Identity.");
        }
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
            String jsonResponse = chatCompletions.getChoices().get(0).getMessage().getContent();
            final T responseModel = new ObjectMapper().readValue(jsonResponse, responseClass);
            return Optional.of(responseModel);
        } catch (Exception e) {
            logger.log(Level.SEVERE, e, () -> "Error calling Judge LLM for evaluation");
        }
        return Optional.empty();
    }

    private List<ChatMessage> getChatMessages(final String systemInstruction, final String userInstruction) {
        return List.of(
                new ChatMessage(ChatRole.SYSTEM).setContent(systemInstruction),
                new ChatMessage(ChatRole.USER).setContent(userInstruction)
        );
    }

    private void isNullOrEmpty(final String value, final String errorMessage) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
    }
}
