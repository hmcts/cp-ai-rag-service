package uk.gov.moj.cp.retrieval;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.ai.logging.LogContext.CLIENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.exception.RedeliveryException;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The async worker's log context: txn= / inv= are on the thread while the pipeline runs and
 * gone when {@code run} returns — on the normal exit and on the redelivery rethrow.
 */
class AnswerGenerationFunctionLogContextTest {

    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";

    private final EmbedDataService embedDataService = mock(EmbedDataService.class);
    private final AzureAISearchService searchService = mock(AzureAISearchService.class);
    private final ResponseGenerationService responseGenerationService = mock(ResponseGenerationService.class);
    private final BlobPersistenceService evalPayloads = mock(BlobPersistenceService.class);
    private final BlobPersistenceService inputChunks = mock(BlobPersistenceService.class);
    private final AnswerGenerationTableService tableService = mock(AnswerGenerationTableService.class);
    private final OutputBinding<String> scoringMessage = mock(OutputBinding.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-async");
        function = new AnswerGenerationFunction(embedDataService, searchService, responseGenerationService,
                evalPayloads, inputChunks, tableService);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("service calls run with txn= and inv= on the thread, and the scope is closed afterwards")
    void pipelineRunsInsideScope_andScopeClosesOnReturn() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        stubClaimableRow(transactionId);

        final Map<String, String> seenByEmbedding = new HashMap<>();
        when(embedDataService.getEmbedding("query")).thenAnswer(invocation -> {
            seenByEmbedding.putAll(MDC.getCopyOfContextMap());
            return List.of(1.0f);
        });
        final List<ChunkedEntry> chunks = List.of(ChunkedEntry.builder().id("1").chunk("c").documentId("d").documentFileName("f").pageNumber(1).build());
        when(searchService.search(isNull(), eq("query"), any(), any())).thenReturn(chunks);
        when(responseGenerationService.generateResponse("query", chunks, "prompt"))
                .thenReturn(new LlmResponse("raw", "formatted", ANSWER_GENERATED));

        function.run(objectMapper.writeValueAsString(payload), scoringMessage, 1, context);

        assertEquals(transactionId.toString(), seenByEmbedding.get(TRANSACTION_ID));
        assertEquals("inv-async", seenByEmbedding.get(INVOCATION_ID));
        assertNull(seenByEmbedding.get(CLIENT_ID), "flag off: no client id on the thread");
        assertNull(MDC.get(TRANSACTION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    @Test
    @DisplayName("the scope is closed on the redelivery rethrow too")
    void scopeClosesOnRedeliveryRethrow() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        when(tableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG, OffsetDateTime.now().plusMinutes(5), "other-worker"));

        assertThrows(RedeliveryException.class,
                () -> function.run(objectMapper.writeValueAsString(payload), scoringMessage, 1, context));

        assertNull(MDC.get(TRANSACTION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    private void stubClaimableRow(final UUID transactionId) throws Exception {
        when(tableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG, null, null));
        when(tableService.claimLease(isNull(), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
    }
}
