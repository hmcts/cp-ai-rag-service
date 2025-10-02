package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingService {

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    private final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class.getName());

    // --- Constructor: Initialize OpenAIClient for Embeddings ---
    public EmbeddingService(String endpoint, String apiKey, String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint for embedding service must be set");
        validateNullOrEmpty(apiKey, "API key for embedding service must be set");
        validateNullOrEmpty(deploymentName, "Deployment name for embedding service must be set.");
        this.embeddingDeploymentName = deploymentName;


        // API Key Authentication (simpler for dev, less secure for prod)
        this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .buildClient();
        LOGGER.info("Initialized Azure OpenAI client with API Key for embeddings.");

    }

    public EmbeddingService(String endpoint, String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable for embedding service must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable for embedding service must be set.");
        this.embeddingDeploymentName = deploymentName;

        // Managed identity authentication (more secure for prod)
        this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        LOGGER.info("Initialized Azure OpenAI client with Managed Identity for embeddings.");
    }

    // --- Method to Embed a single user query string ---
    public List<Double> embedStringData(String content) {
        validateNullOrEmpty(content, "Content to embed cannot be null or empty");

        LOGGER.info("Embedding user query: '{}'", content);

        // The EmbeddingsOptions takes a List of strings. For a single query, it's a list with one element.
        EmbeddingsOptions embeddingsOptions = new EmbeddingsOptions(List.of(content));
        embeddingsOptions.setUser("cp-ai-document-rag-embedding-service");

        try {
            Embeddings embeddingsResult = openAIClient.getEmbeddings(embeddingDeploymentName, embeddingsOptions);

            if (embeddingsResult.getData() != null && !embeddingsResult.getData().isEmpty()) {
                // The API returns a list of embeddings data, one for each input string.
                // We're expecting only one here for a single query.
                List<Double> embedding = embeddingsResult.getData().get(0).getEmbedding();
                LOGGER.info("Successfully embedded query. Obtained dimensions of size : {}", embedding.size());
                return embedding;
            } else {
                LOGGER.warn("No embedding data returned for query: {}", content);
                return null; // Or throw a specific exception
            }
        } catch (Exception e) {
            LOGGER.error("Error embedding query", e);
            // Implement retry logic here if needed (e.g., for 429 errors)
            throw new RuntimeException("Failed to embed query: " + content, e);
        }
    }

}