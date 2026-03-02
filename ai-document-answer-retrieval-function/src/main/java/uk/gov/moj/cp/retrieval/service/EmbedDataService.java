package uk.gov.moj.cp.retrieval.service;

import uk.gov.moj.cp.ai.FunctionEnvironment;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbedDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbedDataService.class);

    private final EmbeddingService embeddingService;


    public EmbedDataService() {
        final FunctionEnvironment env = FunctionEnvironment.get();
        embeddingService = new EmbeddingService(env.embeddingConfig().serviceEndpoint(), env.embeddingConfig().deploymentName());
    }

    EmbedDataService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<Float> getEmbedding(String dataToEmbed) {
        try {
            List<Float> embeddings = embeddingService.embedData(dataToEmbed);
            return (embeddings == null || embeddings.isEmpty()) ? List.of() : embeddings;
        } catch (EmbeddingServiceException e) {
            LOGGER.error("Error embedding data", e);
            return List.of();
        }
    }

}
