package uk.gov.moj.cp.ai.service;

import static java.util.Comparator.comparingLong;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAiClientFactory;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Java SDK ({@code openai-java}) implementation of {@link EmbeddingService}, reproducing
 * {@link AzureEmbeddingService}'s observable semantics on the Azure {@code /openai/v1} passthrough
 * surface: the same abuse-monitoring user tag, one request per batch, empty data → WARN + empty
 * list, and every SDK failure wrapped in {@link EmbeddingServiceException}.
 *
 * <p>Unlike the Azure implementation, which relies implicitly on response array order, results are
 * explicitly re-sorted by {@link Embedding#index()} to restore input order. The
 * {@code chunkVector}↔chunk association downstream is positional, so a silent reordering would
 * mis-associate every vector in a batch.</p>
 */
public class OpenAiEmbeddingService implements EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiEmbeddingService.class);

    private static final String USER_TAG = "cp-ai-document-rag-embedding-service";

    private final OpenAIClient openAIClient;
    private final String embeddingDeploymentName;

    public OpenAiEmbeddingService(final String endpoint, final String deploymentName) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable for embedding service must be set.");
        validateNullOrEmpty(deploymentName, "Deployment name environment variable for embedding service must be set.");
        LOGGER.info("Connecting to embedding service endpoint '{}' and deployment '{}'", endpoint, deploymentName);

        this.openAIClient = OpenAiClientFactory.getInstance(endpoint);
        this.embeddingDeploymentName = deploymentName;
    }

    protected OpenAiEmbeddingService(final OpenAIClient openAIClient, final String deploymentName) {
        this.openAIClient = openAIClient;
        this.embeddingDeploymentName = deploymentName;
    }

    @Override
    public List<Float> embedData(final String content) throws EmbeddingServiceException {
        validateNullOrEmpty(content, "Content to embed cannot be null or empty");
        final List<List<Float>> embeddings = embedCollectionData(List.of(content));
        if (null == embeddings || embeddings.isEmpty()) {
            return List.of();
        }
        return embeddings.get(0);
    }

    @Override
    public List<List<Float>> embedCollectionData(final List<String> contents) throws EmbeddingServiceException {
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("Content list cannot be null or empty");
        }

        LOGGER.info("Embedding {} content strings in batch", contents.size());

        final EmbeddingCreateParams embeddingCreateParams = EmbeddingCreateParams.builder()
                .model(embeddingDeploymentName)
                .inputOfArrayOfStrings(contents)
                .user(USER_TAG)
                .build();

        try {
            final CreateEmbeddingResponse embeddingsResult = openAIClient.embeddings().create(embeddingCreateParams);
            final List<Embedding> data = embeddingsResult.data();

            if (data != null && !data.isEmpty()) {
                final List<List<Float>> embeddings = data.stream()
                        .sorted(comparingLong(Embedding::index))
                        .map(Embedding::embedding)
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
