package uk.gov.moj.cp.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import uk.gov.moj.cp.ai.idempotency.IdempotencyGuard;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The ingestion worker's log context: doc= / inv= are on the thread while the orchestrator runs
 * and gone when {@code run} returns — on the normal exit and on the redelivery rethrow.
 */
class DocumentIngestionFunctionLogContextTest {

    private static final String DOCUMENT_ID_VALUE = "53ac8b90-c4c8-472c-a5ee-fe84ed96047b";
    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";

    private final DocumentIngestionOrchestrator orchestrator = mock(DocumentIngestionOrchestrator.class);
    private final DocumentIngestionOutcomeTableService tableService = mock(DocumentIngestionOutcomeTableService.class);
    private final ExecutionContext context = mock(ExecutionContext.class);

    private DocumentIngestionFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-ingest");
        function = new DocumentIngestionFunction(orchestrator, new IdempotencyGuard(tableService, Duration.ofMinutes(10)));
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("the orchestrator runs with doc= and inv= on the thread, and the scope is closed afterwards")
    void orchestratorRunsInsideScope_andScopeCloses() throws Exception {
        when(tableService.readForClaim(null, DOCUMENT_ID_VALUE))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, null, null));
        when(tableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);
        when(tableService.claimLease(isNull(), eq(DOCUMENT_ID_VALUE), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
        final Map<String, String> seenByOrchestrator = new HashMap<>();
        doAnswer(invocation -> {
            seenByOrchestrator.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(orchestrator).processQueueMessage(any(), any());

        function.run(queueMessage(), 1, context);

        assertEquals(DOCUMENT_ID_VALUE, seenByOrchestrator.get(DOCUMENT_ID));
        assertEquals("inv-ingest", seenByOrchestrator.get(INVOCATION_ID));
        assertNull(seenByOrchestrator.get(TRANSACTION_ID), "document journey: no txn= key");
        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    @Test
    @DisplayName("the scope is closed on the redelivery rethrow too")
    void scopeClosesOnRedeliveryRethrow() throws Exception {
        when(tableService.readForClaim(null, DOCUMENT_ID_VALUE))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, OffsetDateTime.now().plusMinutes(5), "other-worker"));
        when(tableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);

        assertThrows(DocumentProcessingException.class, () -> function.run(queueMessage(), 1, context));

        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    private static String queueMessage() throws Exception {
        return new ObjectMapper().writeValueAsString(new QueueIngestionMetadata(
                DOCUMENT_ID_VALUE, "Burglary-IDPC.pdf", Map.of("case_id", "c1"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf", Instant.now().toString()));
    }
}
