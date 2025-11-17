package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAIClientFactory;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.EmbeddingItem;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddingService {

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    public EmbeddingService(String endpoint, String deploymentName) {

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

    public List<Float> embedData(String content) throws EmbeddingServiceException {
        validateNullOrEmpty(content, "Content to embed cannot be null or empty");
        final List<List<Float>> embeddings = embedCollectionData(List.of(content));
        if (null == embeddings || embeddings.isEmpty()) {
            return List.of();
        }
        return embeddings.get(0);
    }

    public List<List<Float>> embedCollectionData(List<String> contents) throws EmbeddingServiceException {
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
                        .map(EmbeddingItem::getEmbedding)
                        .toList();
                LOGGER.info("Successfully embedded {} queries", embeddings.size());
                return embeddings;
            } else {
                LOGGER.warn("No embedding data returned for content");
                return List.of();
            }
        } catch (Exception e) {
            throw new EmbeddingServiceException("Failed to embed content", e);
        }
    }

}