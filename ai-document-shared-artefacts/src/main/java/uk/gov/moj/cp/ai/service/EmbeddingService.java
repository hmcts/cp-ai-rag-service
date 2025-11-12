package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAIClientFactory;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;
import java.util.stream.Collectors;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    public EmbeddingService(final String endpoint, final String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable for embedding service must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable for embedding service must be set.");
        LOGGER.info("Connecting to embedding service endpoint '{}' and deployment '{}'", endpoint, deploymentName);

        this.openAIClient = OpenAIClientFactory.getInstance(endpoint);
        this.embeddingDeploymentName = deploymentName;
    }

    protected EmbeddingService(final OpenAIClient openAIClient, final String deploymentName) {
        this.openAIClient = openAIClient;
        this.embeddingDeploymentName = deploymentName;
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

    public List<List<Float>> embedStringDataBatch(List<String> contents) throws EmbeddingServiceException {
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("Content list cannot be null or empty");
        }

        LOGGER.info("Embedding {} content strings in batch", contents.size());

        EmbeddingsOptions embeddingsOptions = new EmbeddingsOptions(contents);
        embeddingsOptions.setUser("cp-ai-document-rag-embedding-service");

        try {
            Embeddings embeddingsResult = openAIClient.getEmbeddings(embeddingDeploymentName, embeddingsOptions);

            if (embeddingsResult.getData() != null && !embeddingsResult.getData().isEmpty()) {
                List<List<Float>> embeddings = embeddingsResult.getData().stream()
                        .map(data -> data.getEmbedding().stream()
                                .map(d -> d.floatValue())
                                .collect(Collectors.toList()))
                        .collect(Collectors.toList());
                LOGGER.info("Successfully embedded {} queries. Obtained embeddings with dimensions of size : {}",
                        embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).size());
                return embeddings;
            } else {
                LOGGER.warn("No embedding data returned for batch content");
                return List.of();
            }
        } catch (Exception e) {
            throw new EmbeddingServiceException("Failed to embed batch content", e);
        }
    }

}