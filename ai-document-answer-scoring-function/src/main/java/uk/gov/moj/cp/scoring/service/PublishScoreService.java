package uk.gov.moj.cp.scoring.service;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PublishScoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublishScoreService.class.getName());

    private final AzureMonitorService azureMonitorService;

    public PublishScoreService() {
        azureMonitorService = new AzureMonitorService(System.getenv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING"));
    }

    PublishScoreService(AzureMonitorService azureMonitorService) {
        this.azureMonitorService = azureMonitorService;
    }

    public void publishGroundednessScore(BigDecimal score, String userQuery) {

        LOGGER.info("Publishing Groundedness score for message: {}", score);

        if (Objects.isNull(score) || Objects.isNull(userQuery) || userQuery.isBlank()) {
            LOGGER.warn("Score or user query is null/empty, skipping publishing.");
            return;
        }

        azureMonitorService.publishHistogramScore("ai-rag-service-meter",
                "ai_rag_response_groundedness_score",
                "Distribution of groundedness scores for LLM responses",
                score.doubleValue(),
                "query_type",
                userQuery);
        LOGGER.info("Finished publishing Groundedness score for message");

    }
}
