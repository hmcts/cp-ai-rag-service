package uk.gov.moj.cp.retrieval;

import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getInputChunksFilename;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link AnswerGenerationFunction}
 */
class AnswerGenerationFunctionTest {

    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";

    @Mock
    private EmbedDataService mockEmbedDataService;

    @Mock
    private AzureAISearchService mockSearchService;

    @Mock
    private ResponseGenerationService mockResponseGenerationService;

    @Mock
    private BlobPersistenceService mockBlobPersistenceService;

    @Mock
    private BlobPersistenceService mockBlobPersistenceInputChunksService;

    @Mock
    private AnswerGenerationTableService mockAnswerGenerationTableService;

    @Mock
    private OutputBinding<String> mockScoringOutputBinding;

    @Mock
    private ExecutionContext context;

    private AnswerGenerationFunction function;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        function = new AnswerGenerationFunction(
                mockEmbedDataService,
                mockSearchService,
                mockResponseGenerationService,
                mockBlobPersistenceService,
                mockBlobPersistenceInputChunksService,
                mockAnswerGenerationTableService
        );
    }

    /** The status row is claimable: PENDING, no live lease; the claim returns CLAIM_ETAG. */
    private void stubClaimableRow(final UUID transactionId) throws Exception {
        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG, null, null));
        when(mockAnswerGenerationTableService.claimLease(
                isNull(), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
    }

    @Test
    void run_DoesNothing_WhenQueueMessageIsEmpty() {
        final RuntimeException exception = assertThrows(RuntimeException.class, () ->
                function.run("", mockScoringOutputBinding, 2, context));

        assertThat(exception.getMessage(), is("Retrying AnswerGeneration for transactionId='null'"));

        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockAnswerGenerationTableService, never()).upsertTerminalFenced(
                any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void run_DoesNothing_WhenPayloadIsInvalid() throws Exception {
        AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        null,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        String queueMessage = objectMapper.writeValueAsString(payload);

        function.run(queueMessage, mockScoringOutputBinding, 1, context);

        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockAnswerGenerationTableService, never()).upsertTerminalFenced(
                any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void run_GeneratesAnswer_WhenValidPayloadProvided() throws Exception {
        final UUID transactionId = randomUUID();

        final AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        final String queueMessage = objectMapper.writeValueAsString(payload);

        final List<Float> embeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> chunkedEntries =
                List.of(ChunkedEntry.builder()
                        .id("1")
                        .chunk("Sample content")
                        .documentFileName("doc.pdf")
                        .pageNumber(1)
                        .documentId("doc1")
                        .build());

        stubClaimableRow(transactionId);

        when(mockEmbedDataService.getEmbedding("query"))
                .thenReturn(embeddings);

        when(mockSearchService.search("query", embeddings, payload.metadataFilter()))
                .thenReturn(chunkedEntries);

        when(mockResponseGenerationService.generateResponse(
                "query", chunkedEntries, "prompt"))
                .thenReturn(new LlmResponse("raw response", "generated response", ANSWER_GENERATED));

        function.run(queueMessage, mockScoringOutputBinding, 1, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(getInputChunksFilename(transactionId)),
                eq("generated response"),
                eq(ANSWER_GENERATED),
                eq(null),
                any(OffsetDateTime.class),
                any(Long.class),
                eq(CLAIM_ETAG)
        );

        verify(mockBlobPersistenceService).saveBlob(
                anyString(),
                argThat(
                        json().at("/userQuery").isText("query")
                                .at("/llmResponse").isText("generated response")
                                .at("/queryPrompt").isText("prompt")
                                .at("/chunkedEntries").isArray()
                                .at("/transactionId").isText(transactionId.toString())
                                .toArgumentMatcher()
                )
        );

        verify(mockScoringOutputBinding).setValue(
                argThat(
                        json().at("/filename")
                                .matches("^llm-answer-with-chunks-" + transactionId + ".json$")
                                .toArgumentMatcher()
                )
        );

        // completed successfully — the lease must not be released
        verify(mockAnswerGenerationTableService, never()).releaseLease(any(), anyString(), anyString());
    }

    @Test
    void run_DoesNotGenerateAnswer_WhenValidPayloadProvided() throws Exception {
        final UUID transactionId = randomUUID();

        final AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        final String queueMessage = objectMapper.writeValueAsString(payload);

        final List<Float> embeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> chunkedEntries =
                List.of(ChunkedEntry.builder()
                        .id("1")
                        .chunk("Sample content")
                        .documentFileName("doc.pdf")
                        .pageNumber(1)
                        .documentId("doc1")
                        .build());

        stubClaimableRow(transactionId);

        when(mockEmbedDataService.getEmbedding("query"))
                .thenReturn(embeddings);

        when(mockSearchService.search("query", embeddings, payload.metadataFilter()))
                .thenReturn(chunkedEntries);

        when(mockResponseGenerationService.generateResponse(
                "query", chunkedEntries, "prompt"))
                .thenReturn(new LlmResponse("raw error", "raw formatted error", ANSWER_GENERATION_FAILED));

        function.run(queueMessage, mockScoringOutputBinding, 1, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(getInputChunksFilename(transactionId)),
                eq("raw formatted error"),
                eq(ANSWER_GENERATION_FAILED),
                isNotNull(),
                any(OffsetDateTime.class),
                any(Long.class),
                eq(CLAIM_ETAG)
        );

        // A failed generation (e.g. citation-guard rejection) carries only sentinel text —
        // nothing meaningful to score, so the eval blob and scoring enqueue are skipped.
        verify(mockBlobPersistenceService, never()).saveBlob(anyString(), anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
    }

    // ---- idempotency guard: terminal skip, lease conflicts, fencing ----

    @Test
    void run_SkipsEntirely_WhenRowIsAlreadyTerminal() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATED", READ_ETAG, null, null));
        when(mockAnswerGenerationTableService.isTerminal("ANSWER_GENERATED")).thenReturn(true);

        function.run(queueMessage, mockScoringOutputBinding, 1, context);

        // no expensive work, no writes, no scoring re-enqueue
        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockAnswerGenerationTableService, never()).claimLease(any(), anyString(), anyString(), anyString(), any());
        verify(mockAnswerGenerationTableService, never()).upsertTerminalFenced(
                any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void run_RethrowsForRedelivery_WhenAnotherWorkerHoldsLiveLease() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG,
                        OffsetDateTime.now().plusMinutes(5), "other-worker"));

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> function.run(queueMessage, mockScoringOutputBinding, 1, context));

        assertThat(ex.getMessage(), is(format("Retrying AnswerGeneration for transactionId='%s' (lease held)", transactionId)));
        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockAnswerGenerationTableService, never()).claimLease(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void run_DoesNotWriteFailed_WhenExhaustedAgainstLiveLease() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG,
                        OffsetDateTime.now().plusMinutes(5), "other-worker"));

        assertDoesNotThrow(() -> function.run(queueMessage, mockScoringOutputBinding, 3, context));

        // never overwrite a possibly-completing leaseholder with FAILED
        verify(mockAnswerGenerationTableService, never()).upsertTerminalFenced(
                any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(mockAnswerGenerationTableService, never()).upsertIntoTable(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
    }

    @Test
    void run_ReclaimsExpiredLease_AndProcesses() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        final List<Float> embeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> chunkedEntries = List.of(ChunkedEntry.builder()
                .id("1").chunk("Sample content").documentFileName("doc.pdf").pageNumber(1).documentId("doc1")
                .build());

        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG,
                        OffsetDateTime.now().minusMinutes(5), "crashed-worker"));
        when(mockAnswerGenerationTableService.claimLease(
                isNull(), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);

        when(mockEmbedDataService.getEmbedding("query")).thenReturn(embeddings);
        when(mockSearchService.search("query", embeddings, payload.metadataFilter())).thenReturn(chunkedEntries);
        when(mockResponseGenerationService.generateResponse("query", chunkedEntries, "prompt"))
                .thenReturn(new LlmResponse("raw response", "generated response", ANSWER_GENERATED));

        function.run(queueMessage, mockScoringOutputBinding, 2, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()), eq("query"), eq("prompt"), any(), eq("generated response"),
                eq(ANSWER_GENERATED), eq(null), any(OffsetDateTime.class), any(Long.class), eq(CLAIM_ETAG));
        verify(mockScoringOutputBinding).setValue(anyString());
    }

    @Test
    void run_DiscardsResultWithoutScoring_WhenFencedTerminalWriteIsRejected() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        final List<Float> embeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> chunkedEntries = List.of(ChunkedEntry.builder()
                .id("1").chunk("Sample content").documentFileName("doc.pdf").pageNumber(1).documentId("doc1")
                .build());

        stubClaimableRow(transactionId);
        when(mockEmbedDataService.getEmbedding("query")).thenReturn(embeddings);
        when(mockSearchService.search("query", embeddings, payload.metadataFilter())).thenReturn(chunkedEntries);
        when(mockResponseGenerationService.generateResponse("query", chunkedEntries, "prompt"))
                .thenReturn(new LlmResponse("raw response", "generated response", ANSWER_GENERATED));

        doThrow(new EtagMismatchException("etag changed"))
                .when(mockAnswerGenerationTableService).upsertTerminalFenced(
                        any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // completes silently: no rethrow, result discarded
        assertDoesNotThrow(() -> function.run(queueMessage, mockScoringOutputBinding, 1, context));

        // the eval blob was written before the fenced write (benign overwrite), but scoring never fires
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        // a fence loss is never converted into a FAILED write
        verify(mockAnswerGenerationTableService, never()).upsertIntoTable(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        // and the guard skips the (pointless) release of an already-stale lease
        verify(mockAnswerGenerationTableService, never()).releaseLease(any(), anyString(), anyString());
    }

    // ---- citation guard: retry via queue redelivery, policy at exhaustion ----

    private AnswerGenerationQueuePayload stubGuardScenario(final UUID transactionId) throws Exception {
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final List<Float> embeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> chunkedEntries = List.of(ChunkedEntry.builder()
                .id("1").chunk("Sample content").documentFileName("doc.pdf").pageNumber(1).documentId("doc1")
                .build());
        stubClaimableRow(transactionId);
        when(mockEmbedDataService.getEmbedding("query")).thenReturn(embeddings);
        when(mockSearchService.search("query", embeddings, payload.metadataFilter())).thenReturn(chunkedEntries);
        when(mockResponseGenerationService.generateResponse("query", chunkedEntries, "prompt"))
                .thenThrow(new CitationDegradedException(
                        "Citations missing: jsonBlock=false, inlineMarkers=3, rendered=0, stripped=3",
                        "raw uncited", "uncited formatted"));
        return payload;
    }

    @Test
    void run_RethrowsForQueueRedelivery_WhenCitationDegradedBelowMaxDequeueCount() throws Exception {
        final UUID transactionId = randomUUID();
        final String queueMessage = objectMapper.writeValueAsString(stubGuardScenario(transactionId));

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> function.run(queueMessage, mockScoringOutputBinding, 1, context));

        assertThat(ex.getMessage(), is(format("Retrying AnswerGeneration for transactionId='%s' (citation-degraded)", transactionId)));
        verify(mockAnswerGenerationTableService, never()).upsertTerminalFenced(any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(OffsetDateTime.class), any(Long.class), any());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        // the lease is released before the rethrow so the intentional retry re-claims immediately
        verify(mockAnswerGenerationTableService).releaseLease(null, transactionId.toString(), CLAIM_ETAG);
    }

    @Test
    void run_DeliversDegradedAnswerWithReason_WhenRedeliveryExhausted_InDeliverMode() throws Exception {
        final UUID transactionId = randomUUID();
        final String queueMessage = objectMapper.writeValueAsString(stubGuardScenario(transactionId));

        // dequeueCount == maxDequeueCount (3, env default) → exhaustion policy applies (DELIVER default).
        function.run(queueMessage, mockScoringOutputBinding, 3, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(getInputChunksFilename(transactionId)),
                eq("uncited formatted"),
                eq(ANSWER_GENERATED),
                eq("Citations missing: jsonBlock=false, inlineMarkers=3, rendered=0, stripped=3"),
                any(OffsetDateTime.class),
                any(Long.class),
                eq(CLAIM_ETAG)
        );
        // Delivered answers are real answers: blob persisted and scoring enqueued.
        verify(mockBlobPersistenceService).saveBlob(anyString(), anyString());
        verify(mockScoringOutputBinding).setValue(anyString());
    }

    @Test
    void run_RecordsFailureWithReason_WhenRedeliveryExhausted_InRejectMode() throws Exception {
        function = new AnswerGenerationFunction(
                mockEmbedDataService, mockSearchService, mockResponseGenerationService,
                mockBlobPersistenceService, mockBlobPersistenceInputChunksService,
                mockAnswerGenerationTableService, CitationGuardMode.REJECT);
        final UUID transactionId = randomUUID();
        final String queueMessage = objectMapper.writeValueAsString(stubGuardScenario(transactionId));

        function.run(queueMessage, mockScoringOutputBinding, 3, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(ANSWER_GENERATION_FAILED),
                eq("Citations missing: jsonBlock=false, inlineMarkers=3, rendered=0, stripped=3"),
                any(OffsetDateTime.class),
                any(Long.class),
                eq(CLAIM_ETAG)
        );
        verify(mockBlobPersistenceService, never()).saveBlob(anyString(), anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
    }

    @Test
    void run_UpdatesTableWithFailure_WhenExceptionOccurs_andQueueLevelRetriesExhausted() throws Exception {
        final UUID transactionId = randomUUID();

        final AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        final String queueMessage = objectMapper.writeValueAsString(payload);

        stubClaimableRow(transactionId);
        when(mockEmbedDataService.getEmbedding("query"))
                .thenThrow(new RuntimeException("Embedding failure"));

        function.run(queueMessage, mockScoringOutputBinding, 3, context);

        verify(mockAnswerGenerationTableService).upsertTerminalFenced(
                isNull(),
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(AnswerGenerationStatus.ANSWER_GENERATION_FAILED),
                eq("Embedding failure"),
                any(OffsetDateTime.class),
                any(Long.class),
                eq(CLAIM_ETAG)
        );

        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockBlobPersistenceService, never()).saveBlob(anyString(), anyString());
    }

    @Test
    void run_throwsRuntimeException_whenExceptionOccurs_andQueueLevelRetriesPending() throws Exception {
        final UUID transactionId = randomUUID();

        final AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        final String queueMessage = objectMapper.writeValueAsString(payload);

        stubClaimableRow(transactionId);
        when(mockEmbedDataService.getEmbedding("query")).thenThrow(new RuntimeException("Embedding failure"));

        final RuntimeException exception = assertThrows(RuntimeException.class, () ->
                function.run(queueMessage, mockScoringOutputBinding, 2, context));

        assertThat(exception.getMessage(), is("Retrying AnswerGeneration for transactionId='" + transactionId + "'"));
        // the lease is released before the rethrow so the redelivery can re-claim immediately
        verify(mockAnswerGenerationTableService).releaseLease(null, transactionId.toString(), CLAIM_ETAG);
    }

    @Test
    void run_RethrowsForRedelivery_WhenClaimRaceIsLost() throws Exception {
        final UUID transactionId = randomUUID();
        final AnswerGenerationQueuePayload payload = new AnswerGenerationQueuePayload(
                transactionId, "query", "prompt", List.of(new KeyValuePair("key", "value")));
        final String queueMessage = objectMapper.writeValueAsString(payload);

        when(mockAnswerGenerationTableService.readForClaim(null, transactionId.toString()))
                .thenReturn(new LeaseSnapshot("ANSWER_GENERATION_PENDING", READ_ETAG, null, null));
        when(mockAnswerGenerationTableService.claimLease(
                isNull(), eq(transactionId.toString()), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenThrow(new EtagMismatchException("etag changed"));

        final RuntimeException ex = assertThrows(RuntimeException.class,
                () -> function.run(queueMessage, mockScoringOutputBinding, 1, context));

        assertTrue(ex.getMessage().contains("(lease held)"));
        verify(mockEmbedDataService, never()).getEmbedding(anyString());
    }
}
