package uk.gov.moj.cp.scoring.service;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class PublishScoreServiceTest {

    private PublishScoreService publishScoreService;

    private AzureMonitorService azureMonitorServiceMock;

    private MockedStatic<AzureMonitorService> mockedStaticClass;

    @BeforeEach
    void setUpBeforeEachTest() {
        mockedStaticClass = mockStatic(AzureMonitorService.class);
        azureMonitorServiceMock = mock(AzureMonitorService.class);
        mockedStaticClass.when(AzureMonitorService::getInstance).thenReturn(azureMonitorServiceMock);
        publishScoreService = new PublishScoreService(azureMonitorServiceMock);
    }

    @AfterEach
    void tearDownAfterEachTest() {
        mockedStaticClass.close();
    }


    @Test
    @DisplayName("Publishes groundedness score successfully with valid inputs")
    void publishesGroundednessScoreSuccessfullyWithValidInputs() {
        publishScoreService.publishGroundednessScore(new BigDecimal("4.5"), "factual");

        verify(azureMonitorServiceMock).publishHistogramScore(
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
