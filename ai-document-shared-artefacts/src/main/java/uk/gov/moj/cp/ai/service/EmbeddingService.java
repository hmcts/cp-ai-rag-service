package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAIClientFactory;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    public static EmbeddingService getInstance(final String endpoint, final String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable for embedding service must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable for embedding service must be set.");

        final OpenAIClient openAIClient = OpenAIClientFactory.getInstance(endpoint);

        return new EmbeddingService(openAIClient, deploymentName);
    }

    protected EmbeddingService(final OpenAIClient openAIClient, final String deploymentName) {

        this.openAIClient = openAIClient;
        this.embeddingDeploymentName = deploymentName;

        LOGGER.info("Connecting to embedding service endpoint deployment '{}'", deploymentName);

        LOGGER.info("Initialized Azure OpenAI client with Managed Identity for embeddings.");
    }

    // --- Method to Embed a single user query string ---
    public List<Float> embedStringData(String content) throws EmbeddingServiceException {
        validateNullOrEmpty(content, "Content to embed cannot be null or empty");

        LOGGER.info("Embedding content string");

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
                LOGGER.warn("No embedding data returned for content string");
                return List.of();
            }
        } catch (Exception e) {
            throw new EmbeddingServiceException("Failed to embed content", e);
        }
    }
}