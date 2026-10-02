package uk.gov.moj.cp.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.ORIGIN_INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.BlobService;
import uk.gov.moj.cp.scoring.service.PublishScoreService;
import uk.gov.moj.cp.scoring.service.ScoringService;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The scorer learns its correlation keys from the blob: txn= for an async answer, origin= (the
 * synchronous endpoint's invocation id) for a sync answer — never both.
 */
class AnswerScoringFunctionLogContextTest {

    private static final String QUEUE_MESSAGE = "{\"filename\":\"answer.json\"}";

    private final ScoringService scoringService = mock(ScoringService.class);
    private final PublishScoreService publishScoreService = mock(PublishScoreService.class);
    private final BlobService blobService = mock(BlobService.class);
    private final AnswerGenerationTableService tableService = mock(AnswerGenerationTableService.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final Map<String, String> seenByScorer = new HashMap<>();

    private AnswerScoringFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-scoring");
        when(scoringService.evaluateGroundedness(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            seenByScorer.putAll(MDC.getCopyOfContextMap());
            return new ModelScore(BigDecimal.valueOf(5), "well supported");
        });
        function = new AnswerScoringFunction(scoringService, publishScoreService, blobService, tableService);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("an async answer's blob puts txn= on the thread and no origin=")
    void asyncAnswerCarriesTransactionId() throws Exception {
        when(blobService.readBlob("answer.json", ScoringPayload.class))
                .thenReturn(new ScoringPayload("q", "a", "p", List.of(), "txn-1", null, null));

        function.run(QUEUE_MESSAGE, 1, context);

        assertEquals("txn-1", seenByScorer.get(TRANSACTION_ID));
        assertEquals("inv-scoring", seenByScorer.get(INVOCATION_ID));
        assertNull(seenByScorer.get(ORIGIN_INVOCATION_ID));
        assertNull(MDC.get(TRANSACTION_ID));
    }

    @Test
    @DisplayName("a sync answer's blob puts origin= on the thread and no txn=")
    void syncAnswerCarriesOriginInvocationId() throws Exception {
        when(blobService.readBlob("answer.json", ScoringPayload.class))
                .thenReturn(new ScoringPayload("q", "a", "p", List.of(), null, null, "inv-sync"));

        function.run(QUEUE_MESSAGE, 1, context);

        assertEquals("inv-sync", seenByScorer.get(ORIGIN_INVOCATION_ID));
        assertNull(seenByScorer.get(TRANSACTION_ID));
        assertNull(MDC.get(ORIGIN_INVOCATION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    @Test
    @DisplayName("the scope is closed when the function exits by exception")
    void scopeClosesOnFailure() throws Exception {
        when(blobService.readBlob("answer.json", ScoringPayload.class))
                .thenReturn(new ScoringPayload("q", "a", "p", List.of(), "txn-1"));
        when(scoringService.evaluateGroundedness(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("judge unavailable"));

        assertThrows(RuntimeException.class, () -> function.run(QUEUE_MESSAGE, 2, context));

        assertNull(MDC.get(TRANSACTION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }
}
