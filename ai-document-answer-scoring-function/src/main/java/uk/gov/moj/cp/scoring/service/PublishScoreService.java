package uk.gov.moj.cp.scoring.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PublishScoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublishScoreService.class);

    private final AzureMonitorService azureMonitorService;

    public PublishScoreService() {
        azureMonitorService = AzureMonitorService.getInstance();
    }

    PublishScoreService(AzureMonitorService azureMonitorService) {
        this.azureMonitorService = azureMonitorService;
    }

    public void publishGroundednessScore(BigDecimal score, String userQuery) {
        publishGroundednessScore(score, userQuery, null);
    }

    /**
     * Client-scoped variant: publishes the groundedness score with an additional per-client
     * telemetry dimension ({@code client_id}) alongside {@code query_type}, so scores can be
     * segmented per client.
     */
    public void publishGroundednessScore(BigDecimal score, String userQuery, String clientId) {

        LOGGER.info("Publishing Groundedness score for message: {}", score);

        if (Objects.isNull(score) || Objects.isNull(userQuery) || userQuery.isBlank()) {
            LOGGER.warn("Score or user query is null/empty, skipping publishing.");
            return;
        }

        final Map<String, String> dimensions = new LinkedHashMap<>();
        dimensions.put("query_type", userQuery);
        if (clientId != null) {
            dimensions.put("client_id", clientId);
        }
        azureMonitorService.publishHistogramScore(
                "ai_rag_response_groundedness_score",
                "Distribution of groundedness scores for LLM responses",
                score.doubleValue(),
                dimensions);
        LOGGER.info("Finished publishing Groundedness score for message");
    }
}
