package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_ENDPOINT;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbedDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbedDataService.class);

    private final EmbeddingService embeddingService;

    public EmbedDataService() {

        String endpoint = System.getenv(AZURE_EMBEDDING_SERVICE_ENDPOINT);
        String deploymentName = System.getenv(AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME);
        embeddingService = new EmbeddingService(endpoint, deploymentName);
    }

    EmbedDataService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<Double> getEmbedding(String dataToEmbed) {
        try {
            List<Double> embeddings = embeddingService.embedStringData(dataToEmbed);
            return (embeddings == null || embeddings.isEmpty()) ? List.of() : embeddings;
        } catch (EmbeddingServiceException e) {
            LOGGER.error("Error embedding data", e);
            return List.of();
        }
    }

}
