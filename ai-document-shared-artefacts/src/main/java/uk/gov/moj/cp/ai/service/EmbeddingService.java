package uk.gov.moj.cp.ai.service;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

/**
 * Provider-agnostic embedding contract. Implemented by {@link AzureEmbeddingService} (azure-ai-openai,
 * scheduled for deletion at Stage 4 / DD-43424) and {@code OpenAiEmbeddingService} (openai-java);
 * selected at construction by {@code EmbeddingServiceFactory} via {@code EMBEDDING_SERVICE_PROVIDER}.
 *
 * <p>Signatures are identical to the pre-extraction concrete class, so no consumer import, signature
 * or mock changes (FR-4 / AC-7).</p>
 */
public interface EmbeddingService {

    List<Float> embedData(String content) throws EmbeddingServiceException;

    List<List<Float>> embedCollectionData(List<String> contents) throws EmbeddingServiceException;
}
