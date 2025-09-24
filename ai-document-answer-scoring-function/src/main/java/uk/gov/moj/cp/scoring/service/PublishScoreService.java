package uk.gov.moj.cp.scoring.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PublishScoreService {

    private final Logger logger = Logger.getLogger(PublishScoreService.class.getName());

    private final AzureMonitorService azureMonitorService;

    public PublishScoreService() {
        azureMonitorService = new AzureMonitorService(System.getenv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING"));
    }

    PublishScoreService(AzureMonitorService azureMonitorService) {
        this.azureMonitorService = azureMonitorService;
    }

    public void publishGroundednessScore(BigDecimal score, String userQuery) {

        logger.log(Level.INFO, () -> "Publishing Groundedness score for message: " + score);

        if (Objects.isNull(score) || Objects.isNull(userQuery) || userQuery.isBlank()) {
            logger.log(Level.WARNING, "Score or user query is null/empty, skipping publishing.");
            return;
        }

        azureMonitorService.publishHistogramScore("ai-rag-service-meter",
                "ai_rag_response_groundedness_score",
                "Distribution of groundedness scores for LLM responses",
                score.doubleValue(),
                "query_type",
                userQuery);
        logger.log(Level.INFO, "Finished publishing Groundedness score for message");

    }
}
