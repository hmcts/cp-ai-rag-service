package uk.gov.moj.cp.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.BlobService;
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
    private AnswerGenerationTableService answerGenerationTableService;
    private BlobService blobService;
    private ExecutionContext contextMock;

    @BeforeEach
    void setUp() {
        scoringServiceMock = mock(ScoringService.class);
        publishScoreService = mock(PublishScoreService.class);
        answerGenerationTableService = mock(AnswerGenerationTableService.class);
        blobService = mock(BlobService.class);
        contextMock = mock(ExecutionContext.class);
        answerScoringFunction = new AnswerScoringFunction(scoringServiceMock, publishScoreService, blobService, answerGenerationTableService);
    }

    @Test
    @DisplayName("Processes valid message and logs success")
    void processesValidMessageAndLogsSuccess() throws JsonProcessingException, BlobParsingException {
        String queueMessage = "{\"filename\":\"test123.pdf\"}";
        String blobMessage = "{\"llmResponse\":\"response\",\"userQuery\":\"query\",\"chunkedEntries\":[],\"transactionId\":\"12345\"}";
        final BigDecimal llmScore = BigDecimal.valueOf(5);
        final String userQuery = "query";

        ModelScore modelScore = new ModelScore(llmScore, "Well supported");

        when(blobService.readBlob("test123.pdf", ScoringPayload.class)).thenReturn(getObjectMapper().readValue(blobMessage, ScoringPayload.class));

        when(scoringServiceMock.evaluateGroundedness("response", userQuery, List.of()))
                .thenReturn(modelScore);

        answerScoringFunction.run(queueMessage, contextMock);

        verify(scoringServiceMock).evaluateGroundedness("response", userQuery, List.of());
        verify(publishScoreService).publishGroundednessScore(llmScore, userQuery);
        verify(answerGenerationTableService).recordGroundednessScore("12345", llmScore);
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

    }

    @Test
    @DisplayName("Handles scoring service failure and logs error")
    void handlesScoringServiceFailureAndLogsError() throws Exception {
        String queueMessage = "{\"filename\":\"test123.pdf\"}";
        String blobMessage = "{\"llmResponse\":\"response\",\"userQuery\":\"query\",\"chunkedEntries\":[],\"transactionId\":\"12345\"}";

        when(blobService.readBlob("test123.pdf", ScoringPayload.class)).thenReturn(getObjectMapper().readValue(blobMessage, ScoringPayload.class));

        when(scoringServiceMock.evaluateGroundedness("response", "query", List.of()))
                .thenThrow(new RuntimeException("Scoring service error"));

        Exception exception = assertThrows(RuntimeException.class, () -> {
            answerScoringFunction.run(queueMessage, contextMock);
        });

        assertEquals("Scoring service error", exception.getMessage());
    }
}
