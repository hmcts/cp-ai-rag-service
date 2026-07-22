package uk.gov.moj.cp.scoring.service;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * The client-scoped groundedness publish records the histogram with a second {@code client_id}
 * dimension alongside {@code query_type}, so scores can be segmented per client.
 */
class PublishScoreServiceClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";

    private PublishScoreService publishScoreService;
    private AzureMonitorService azureMonitorService;
    private MockedStatic<AzureMonitorService> mockedStatic;

    @BeforeEach
    void setUp() {
        mockedStatic = mockStatic(AzureMonitorService.class);
        azureMonitorService = mock(AzureMonitorService.class);
        mockedStatic.when(AzureMonitorService::getInstance).thenReturn(azureMonitorService);
        publishScoreService = new PublishScoreService(azureMonitorService);
    }

    @AfterEach
    void tearDown() {
        mockedStatic.close();
    }

    @Test
    @DisplayName("publishes the groundedness histogram with a client_id dimension")
    void shouldPublishWithClientDimension() {
        publishScoreService.publishGroundednessScore(new BigDecimal("4.5"), "factual", CLIENT_ID);

        verify(azureMonitorService).publishHistogramScore(
                eq("ai_rag_response_groundedness_score"),
                eq("Distribution of groundedness scores for LLM responses"),
                eq(4.5),
                eq(java.util.Map.of("query_type", "factual", "client_id", CLIENT_ID)));
    }
}
