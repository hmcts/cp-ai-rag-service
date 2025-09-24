package uk.gov.moj.cp.scoring.service;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublishScoreServiceTest {

    private AzureMonitorService azureMonitorServiceMock;
    private PublishScoreService publishScoreService;

    @BeforeEach
    void setUp() {
        azureMonitorServiceMock = mock(AzureMonitorService.class);
        publishScoreService = new PublishScoreService(azureMonitorServiceMock);
    }

    @Test
    @DisplayName("Publishes groundedness score successfully with valid inputs")
    void publishesGroundednessScoreSuccessfullyWithValidInputs() {
        publishScoreService.publishGroundednessScore(new BigDecimal("4.5"), "factual");

        verify(azureMonitorServiceMock).publishHistogramScore(
                eq("ai-rag-service-meter"),
                eq("ai_rag_response_groundedness_score"),
                eq("Distribution of groundedness scores for LLM responses"),
                eq(4.5),
                eq("query_type"),
                eq("factual")
        );
    }

    @Test
    @DisplayName("Handles null score gracefully without publishing")
    void handlesNullScoreGracefullyWithoutPublishing() {
        publishScoreService.publishGroundednessScore(null, "factual");

        verifyNoInteractions(azureMonitorServiceMock);
    }

    @Test
    @DisplayName("Handles null query type gracefully without publishing")
    void handlesNullQueryTypeGracefullyWithoutPublishing() {
        publishScoreService.publishGroundednessScore(new BigDecimal("4.5"), null);

        verifyNoInteractions(azureMonitorServiceMock);
    }

    @Test
    @DisplayName("Handles empty query type gracefully without publishing")
    void handlesEmptyQueryTypeGracefullyWithoutPublishing() {
        publishScoreService.publishGroundednessScore(new BigDecimal("4.5"), "");

        verifyNoInteractions(azureMonitorServiceMock);
    }
}
