package uk.gov.moj.cp.retrieval;

import static java.util.UUID.randomUUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;

import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Enforcement wiring for the async answer-generation worker: the clientId carried on the queue
 * payload is used to claim the idempotency lease and to fence the terminal write (both on the same
 * client namespace, on every redelivery), threaded into search, and threaded into the no-claim
 * FAILED fallback so a defensively-created failure row lands in the client's partition.
 */
class AnswerGenerationFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";

    @Mock
    private EmbedDataService embedDataService;
    @Mock
    private AzureAISearchService searchService;
    @Mock
    private ResponseGenerationService responseGenerationService;
    @Mock
    private BlobPersistenceService blobPersistenceEvalPayloadsService;
    @Mock
    private BlobPersistenceService blobPersistenceInputChunksService;
    @Mock
    private AnswerGenerationTableService tableService;
    @Mock
    private OutputBinding<String> scoringMessage;
    @Mock
    private ExecutionContext context;

    private AnswerGenerationFunction function;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        function = new AnswerGenerationFunction(embedDataService, searchService, responseGenerationService,
                blobPersistenceEvalPayloadsService, blobPersistenceInputChunksService, tableService);
    }

    private AnswerGenerationQueuePayload payloadWithClientId(final UUID transactionId) {
        return new AnswerGenerationQueuePayload(transactionId, "query", "prompt",
                List.of(new KeyValuePair("key", "value")), CLIENT_ID);
    }

    private void stubClaimableRow(final UUID transactionId) throws Exception {
        when(tableService.readForClaim(any(), eq(transactionId.toString())))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG, null, null));
        when(tableService.claimLease(any(), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
    }

    private void stubGeneration() throws Exception {
        final List<ChunkedEntry> chunks = List.of(ChunkedEntry.builder()
                .id("1").chunk("content").documentFileName("doc.pdf").pageNumber(1).documentId("doc1").build());
        when(embedDataService.getEmbedding("query")).thenReturn(List.of(1.0f));
        when(searchService.search(any(), eq("query"), any(), any())).thenReturn(chunks);
        when(responseGenerationService.generateResponse(eq("query"), any(), eq("prompt")))
                .thenReturn(new LlmResponse("raw", "generated", ANSWER_GENERATED));
    }

    @Test
    @DisplayName("claims the lease on the payload's client namespace and fences the terminal write with the same client id")
    void shouldClaimAndFenceUnderPayloadClientId() throws Exception {
        final UUID transactionId = randomUUID();
        stubClaimableRow(transactionId);
        stubGeneration();

        function.run(objectMapper.writeValueAsString(payloadWithClientId(transactionId)), scoringMessage, 1, context);

        verify(tableService).claimLease(eq(CLIENT_ID), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class));
        verify(tableService).upsertTerminalFenced(eq(CLIENT_ID), eq(transactionId.toString()), any(), any(), any(), any(), any(), any(), any(), any(), eq(CLAIM_ETAG));
    }

    @Test
    @DisplayName("threads the payload client id into the search scope")
    void shouldThreadClientIdIntoSearch() throws Exception {
        final UUID transactionId = randomUUID();
        stubClaimableRow(transactionId);
        stubGeneration();

        function.run(objectMapper.writeValueAsString(payloadWithClientId(transactionId)), scoringMessage, 1, context);

        verify(searchService).search(eq(CLIENT_ID), eq("query"), any(), any());
    }

    @Test
    @DisplayName("no-claim FAILED fallback writes the failure row under the client partition")
    void shouldWriteFallbackFailedRowUnderClientId() throws Exception {
        final UUID transactionId = randomUUID();
        // The claim read fails, then the safe-fallback re-read finds no row → a FAILED row is created.
        when(tableService.readForClaim(any(), eq(transactionId.toString())))
                .thenThrow(new EntityRetrievalException("read failure"))
                .thenReturn(null);

        // dequeueCount == maxDequeueCount (3, env default) → exhaustion → no-claim fallback runs.
        function.run(objectMapper.writeValueAsString(payloadWithClientId(transactionId)), scoringMessage, 3, context);

        verify(tableService).upsertIntoTable(eq(CLIENT_ID), eq(transactionId.toString()),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("legacy payload without a client id keeps the null-scoped claim and search")
    void shouldClaimWithoutScope_whenLegacyPayload() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload legacy = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        stubClaimableRow(transactionId);
        stubGeneration();

        function.run(objectMapper.writeValueAsString(legacy), scoringMessage, 1, context);

        verify(searchService).search(isNull(), eq("query"), any(), any());
        verify(tableService).upsertTerminalFenced(isNull(), eq(transactionId.toString()), any(), any(), any(), any(), any(), any(), any(), any(), eq(CLAIM_ETAG));
    }
}
