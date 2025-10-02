package uk.gov.moj.cp.retrieval.service;

import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

public class EmbedDataService {

    private final EmbeddingService embeddingService;

    public EmbedDataService() {

        String endpoint = System.getenv("AZURE_EMBEDDING_SERVICE_ENDPOINT");
        String apiKey = System.getenv("AZURE_EMBEDDING_SERVICE_API_KEY");
        String deploymentName = System.getenv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME");
        embeddingService = new EmbeddingService(endpoint, apiKey, deploymentName);

    }

    EmbedDataService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<Double> getEmbedding(String dataToEmbed) {
        final List<Double> embeddings = embeddingService.embedStringData(dataToEmbed);
        if (null == embeddings || embeddings.isEmpty()) {
            throw new IllegalStateException("Failed to generate embeddings for the provided data.");
        }
        return embeddings;
    }
}
