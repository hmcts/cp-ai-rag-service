package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingService {

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    public EmbeddingService(String endpoint, String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable for embedding service must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable for embedding service must be set.");
        this.embeddingDeploymentName = deploymentName;

        this.openAIClient = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        LOGGER.info("Initialized Azure OpenAI client with Managed Identity for embeddings.");
    }

    // --- Method to Embed a single user query string ---
    public List<Float> embedStringData(String content) throws EmbeddingServiceException {
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
                List<Float> embedding = embeddingsResult.getData().get(0).getEmbedding();
                LOGGER.info("Successfully embedded query. Obtained dimensions of size : {}", embedding.size());
                return embedding;
            } else {
                LOGGER.warn("No embedding data returned for query: {}", content);
                return List.of();
            }
        } catch (Exception e) {
            // Implement retry logic here if needed (e.g., for 429 errors)
            throw new EmbeddingServiceException("Failed to embed content", e);
        }
    }

}