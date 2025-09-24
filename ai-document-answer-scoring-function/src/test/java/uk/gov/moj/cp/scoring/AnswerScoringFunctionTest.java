package uk.gov.moj.cp.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.PublishScoreService;
import uk.gov.moj.cp.scoring.service.ScoringService;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerScoringFunctionTest {

    private AnswerScoringFunction answerScoringFunction;
    private ScoringService scoringServiceMock;
    private PublishScoreService publishScoreService;
    private ExecutionContext contextMock;

    @BeforeEach
    void setUp() {
        scoringServiceMock = mock(ScoringService.class);
        publishScoreService = mock(PublishScoreService.class);
        contextMock = mock(ExecutionContext.class);
        answerScoringFunction = new AnswerScoringFunction(scoringServiceMock, publishScoreService);
    }

    @Test
    @DisplayName("Processes valid message and logs success")
    void processesValidMessageAndLogsSuccess() {
        String message = "{\"llmResponse\":\"response\",\"userQuery\":\"query\",\"chunkedEntries\":[]}";
        final BigDecimal llmScore = BigDecimal.valueOf(5);
        final String userQuery = "query";

        ModelScore modelScore = new ModelScore(llmScore, "Well supported");

        when(scoringServiceMock.evaluateGroundedness("response", userQuery, List.of()))
                .thenReturn(modelScore);

        answerScoringFunction.run(message, contextMock);

        verify(scoringServiceMock).evaluateGroundedness("response", userQuery, List.of());
        verify(publishScoreService).publishGroundednessScore(llmScore, userQuery);
    }

    @Test
    @DisplayName("Handles invalid JSON message and logs error")
    void handlesInvalidJsonMessageAndLogsError() {
        String invalidMessage = "invalid-json";

        Exception exception = assertThrows(RuntimeException.class, () -> {
            answerScoringFunction.run(invalidMessage, contextMock);
        });

        assertTrue(exception.getCause() instanceof JsonProcessingException);
    }

    @Test
    @DisplayName("Handles null message and logs error")
    void handlesNullMessageAndLogsError() {
        String nullMessage = null;

        Exception exception = assertThrows(RuntimeException.class, () -> {
            answerScoringFunction.run(nullMessage, contextMock);
        });

        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Handles scoring service failure and logs error")
    void handlesScoringServiceFailureAndLogsError() throws Exception {
        String message = "{\"llmResponse\":\"response\",\"userQuery\":\"query\",\"chunkedEntries\":[]}";

        when(scoringServiceMock.evaluateGroundedness("response", "query", List.of()))
                .thenThrow(new RuntimeException("Scoring service error"));

        Exception exception = assertThrows(RuntimeException.class, () -> {
            answerScoringFunction.run(message, contextMock);
        });

        assertEquals("Scoring service error", exception.getMessage());
    }
}
