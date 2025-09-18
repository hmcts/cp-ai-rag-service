package uk.gov.moj.cp.scoring.service;

import uk.gov.moj.cp.scoring.model.ChunkedEntry;
import uk.gov.moj.cp.scoring.model.ModelScore;

import java.util.ArrayList;
import java.util.List;
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

public class ScoringService {

    private static final String JUDGE_LLM_PROMPT_TEMPLATE = """
            You are an expert evaluator. Your task is to rate the groundedness of an answer based on a set of provided documents.
            Groundedness is defined as the degree to which every claim in the answer is directly and explicitly supported by the documents.
            Use a rating scale from 1 to 5, where:
            1: None of the claims in the answer are supported by the documents.
            2: A small portion of the claims are supported.
            3: Some of the claims are supported, but there are significant ungrounded statements.
            4: Most of the claims are supported, with only minor, ungrounded statements.
            5: Every claim in the answer is explicitly and directly supported by the documents.
            
            Your output MUST be a JSON object with the following keys:
            {
              "groundedness_score": <Your numeric score 1-5>,
              "reasoning": "<A brief explanation for the score>"
            }
            
            --- Provided Documents ---
            %s
            ---
            User Query: %s
            Answer to Evaluate: %s
            """;

    private final OpenAIClient judgeOpenAIClient;
    private final String judgeChatDeploymentName;
    private final Logger logger = Logger.getLogger(ScoringService.class.getName());

    // --- Constructor: Initialize OpenAIClient ---
    public ScoringService() {

        String judgeModelEndpoint = System.getenv("AZURE_JUDGE_OPENAI_ENDPOINT");
        String judgeModelKey = System.getenv("AZURE_JUDGE_OPENAI_API_KEY");
        this.judgeChatDeploymentName = System.getenv("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME");

        if (judgeModelEndpoint == null || judgeModelEndpoint.isEmpty()) {
            throw new IllegalArgumentException("AZURE_JUDGE_OPENAI_ENDPOINT environment variable must be set.");
        }
        if (judgeChatDeploymentName == null || judgeChatDeploymentName.isEmpty()) {
            throw new IllegalArgumentException("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME environment variable must be set.");
        }

        // Choose authentication method: API Key or Managed Identity
        if (judgeModelKey != null && !judgeModelKey.isEmpty()) {
            // Option 1: API Key Authentication (simpler for dev, less secure for prod)
            this.judgeOpenAIClient = new OpenAIClientBuilder()
                    .endpoint(judgeModelEndpoint)
                    .credential(new AzureKeyCredential(judgeModelKey))
                    .buildClient();


            logger.info("Initialized Azure OpenAI client with API Key.");
        } else {
            // Option 2: Azure Managed Identity (Recommended for production)
            // Ensure your Azure App Service/Function App has a Managed Identity enabled
            // and granted 'Cognitive Services OpenAI User' role on your Azure OpenAI resource.
            this.judgeOpenAIClient = new OpenAIClientBuilder()
                    .endpoint(judgeModelEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            logger.info("Initialized Azure OpenAI client with Managed Identity.");
        }
    }

    /**
     * Evaluates the groundedness of an LLM response using a Judge LLM.
     * The Judge LLM is prompted to score the response based on the provided context.
     *
     * @param llmResponse The response from the generator LLM.
     * @param userQuery The original user query.
     * @param retrievedDocuments The documents retrieved from search.
     * @return The groundedness score from the judge, or a default value on error.
     */
    public ModelScore evaluateGroundedness(String llmResponse, String userQuery, List<ChunkedEntry> retrievedDocuments) {
        logger.info("Evaluating groundedness of response with Judge LLM...");

        // Construct the context string for the Judge LLM
        StringBuilder contextBuilder = new StringBuilder();
        if (retrievedDocuments != null && !retrievedDocuments.isEmpty()) {
            for (ChunkedEntry entry : retrievedDocuments) {
                String content = entry.chunk();
                String fileName = entry.documentFileName();
                Integer pageNumber = entry.pageNumber();

                contextBuilder.append("Document: ").append(fileName);
                if (pageNumber != null) {
                    contextBuilder.append(", Page: ").append(pageNumber);
                }
                contextBuilder.append("\nContent: ").append(content).append("\n\n");
            }
        }
        final List<ChatMessage> chatMessages = getChatMessages(llmResponse, userQuery, contextBuilder);

        ChatCompletionsOptions chatCompletionsOptions = new ChatCompletionsOptions(chatMessages)
                .setMaxTokens(200) // Keep the judge's response concise
                .setTemperature(0.0) // Low temperature for deterministic scoring
                .setTopP(0.0)
                .setStream(false);

        try {
            ChatCompletions chatCompletions = judgeOpenAIClient.getChatCompletions(judgeChatDeploymentName, chatCompletionsOptions);

            if (chatCompletions.getChoices() != null && !chatCompletions.getChoices().isEmpty()) {
                String jsonResponse = chatCompletions.getChoices().get(0).getMessage().getContent();

                // Parse the JSON response from the judge
                return new ObjectMapper().readValue(jsonResponse, ModelScore.class);

            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, e, () -> "Error calling Judge LLM for evaluation");
        }

        return new ModelScore(0.0, "Error generating score"); // Return a default score of 0.0 on error
    }

    private List<ChatMessage> getChatMessages(final String llmResponse, final String userQuery, final StringBuilder contextBuilder) {
        String retrievedContextsString = contextBuilder.toString();

        // Define the prompt for the Judge LLM
        String judgePromptContent = getJudgeLLMPrompt(llmResponse, userQuery, retrievedContextsString);

        List<ChatMessage> chatMessages = new ArrayList<>();
        final ChatMessage e1 = new ChatMessage(ChatRole.SYSTEM);
        e1.setContent(judgePromptContent);
        final ChatMessage e2 = new ChatMessage(ChatRole.USER);
        e2.setContent("Evaluate the answer.");
        chatMessages.add(e1);
        chatMessages.add(e2);
        return chatMessages;
    }

    private String getJudgeLLMPrompt(final String llmResponse, final String userQuery, final String retrievedContextsString) {
        return String.format(
                JUDGE_LLM_PROMPT_TEMPLATE,
                retrievedContextsString,
                userQuery,
                llmResponse
        );
    }
}