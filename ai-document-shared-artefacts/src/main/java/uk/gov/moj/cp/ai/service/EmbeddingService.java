package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.langfuse.LangfuseConfig.getTracer;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.OpenAIClientFactory;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.EmbeddingItem;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.ai.openai.models.EmbeddingsUsage;
import io.opentelemetry.api.trace.Span;
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
            final EmbeddingsUsage usage = embeddingsResult.getUsage();
            setTracingDetails(usage, contents);

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

    private void setTracingDetails(EmbeddingsUsage usage, List<String> contents) {

        Span embeddingSpan = getTracer().spanBuilder("embedding").startSpan();
        embeddingSpan.setAttribute("gen_ai.system", "openai");
        embeddingSpan.setAttribute("gen_ai.request.model", embeddingDeploymentName);
        // 1. Set the Input (Show the first few items or join them)
        String inputSummary = String.join(" | ", contents.stream().limit(3).toList());
        if (contents.size() > 3) inputSummary += "... (total " + contents.size() + ")";
        embeddingSpan.setAttribute("input.value", inputSummary);

        if (null != usage) {
            embeddingSpan.setAttribute("gen_ai.usage.input_tokens", usage.getPromptTokens());
            embeddingSpan.setAttribute("gen_ai.usage.total_tokens", usage.getTotalTokens());
        }
        embeddingSpan.end();

    }

}