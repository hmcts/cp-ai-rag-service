package uk.gov.moj.cp.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.idempotency.IdempotencyGuard;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ai.util.ObjectMapperFactory;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionFunctionTest {

    private static final String DOCUMENT_ID = "53ac8b90-c4c8-472c-a5ee-fe84ed96047b";
    private static final String CLAIM_ETAG = "W/\"claimed\"";
    private static final String READ_ETAG = "W/\"read\"";

    @Mock
    private DocumentIngestionOrchestrator documentIngestionOrchestrator;

    @Mock
    private DocumentIngestionOutcomeTableService outcomeTableService;

    private DocumentIngestionFunction documentIngestionFunction;

    @BeforeEach
    void setUp() {
        final IdempotencyGuard idempotencyGuard = new IdempotencyGuard(outcomeTableService, Duration.ofMinutes(10));
        documentIngestionFunction = new DocumentIngestionFunction(documentIngestionOrchestrator, idempotencyGuard);
    }

    private QueueIngestionMetadata metadata() {
        return new QueueIngestionMetadata(
                DOCUMENT_ID,
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString()
        );
    }

    private String queueMessage(final QueueIngestionMetadata metadata) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(metadata);
    }

    /** Row is claimable: non-terminal status, no live lease; the claim returns CLAIM_ETAG. */
    private void stubClaimableRow() throws Exception {
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, null, null));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);
        when(outcomeTableService.claimLease(isNull(), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
    }

    @Test
    @DisplayName("Process Queue Message Successfully under a claimed lease")
    void shouldProcessQueueMessageSuccessfully() throws Exception {
        // given
        final QueueIngestionMetadata metadata = metadata();
        stubClaimableRow();

        // when
        documentIngestionFunction.run(queueMessage(metadata), 1);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata, new ClaimToken(null, DOCUMENT_ID, CLAIM_ETAG));
    }

    @Test
    @DisplayName("Handle Empty Queue Message")
    void shouldHandleEmptyQueueMessage() throws Exception {
        // given
        final String emptyMessage = "";

        // when
        documentIngestionFunction.run(emptyMessage, 1);

        // then
        // Empty messages should return early without calling orchestrator
        verify(documentIngestionOrchestrator, never()).processQueueMessage(any(), any());
        verifyNoInteractions(outcomeTableService);
    }

    @Test
    @DisplayName("Skip processing entirely when the status row is already terminal")
    void shouldSkipWhenRowIsTerminal() throws Exception {
        // given
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("INGESTION_SUCCESS", READ_ETAG, null, null));
        when(outcomeTableService.isTerminal("INGESTION_SUCCESS")).thenReturn(true);

        // when
        documentIngestionFunction.run(queueMessage(metadata()), 1);

        // then
        verifyNoInteractions(documentIngestionOrchestrator);
        verify(outcomeTableService, never()).claimLease(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Rethrow to redeliver when another worker holds a live lease and budget remains")
    void shouldRethrowWhenLiveLeaseHeldAndBudgetRemains() throws Exception {
        // given
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, OffsetDateTime.now().plusMinutes(5), "other-worker"));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);

        // when & then
        final DocumentProcessingException exception = assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage(metadata()), 1));
        assertTrue(exception.getMessage().contains("Lease held by another worker"));
        verifyNoInteractions(documentIngestionOrchestrator);
        verify(outcomeTableService, never()).claimLease(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Do NOT write FAILED when attempts exhaust against a live lease — leave the outcome to the leaseholder")
    void shouldNotWriteFailedWhenExhaustedAgainstLiveLease() throws Exception {
        // given
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, OffsetDateTime.now().plusMinutes(5), "other-worker"));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);

        // when
        assertDoesNotThrow(() -> documentIngestionFunction.run(queueMessage(metadata()), 3));

        // then
        verifyNoInteractions(documentIngestionOrchestrator);
    }

    @Test
    @DisplayName("Reclaim an expired lease and process")
    void shouldReclaimExpiredLease() throws Exception {
        // given
        final QueueIngestionMetadata metadata = metadata();
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, OffsetDateTime.now().minusMinutes(5), "crashed-worker"));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);
        when(outcomeTableService.claimLease(isNull(), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);

        // when
        documentIngestionFunction.run(queueMessage(metadata), 1);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata, new ClaimToken(null, DOCUMENT_ID, CLAIM_ETAG));
    }

    @Test
    @DisplayName("Rethrow to redeliver when the claim race is lost")
    void shouldRethrowWhenClaimRaceLost() throws Exception {
        // given
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, null, null));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);
        when(outcomeTableService.claimLease(isNull(), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenThrow(new EtagMismatchException("etag changed"));

        // when & then
        assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage(metadata()), 1));
        verifyNoInteractions(documentIngestionOrchestrator);
    }

    @Test
    @DisplayName("Create a claimed row defensively when the status row is missing")
    void shouldCreateClaimedRowWhenRowMissing() throws Exception {
        // given
        final QueueIngestionMetadata metadata = metadata();
        when(outcomeTableService.readForClaim(null, DOCUMENT_ID)).thenReturn(null);
        when(outcomeTableService.createClaimedRow(isNull(), eq(DOCUMENT_ID), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);

        // when
        documentIngestionFunction.run(queueMessage(metadata), 1);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata, new ClaimToken(null, DOCUMENT_ID, CLAIM_ETAG));
    }

    @Test
    @DisplayName("Complete silently (no FAILED write, no rethrow) when the fenced terminal write is rejected")
    void shouldCompleteSilentlyOnFencedWriteRejection() throws Exception {
        // given
        stubClaimableRow();
        doThrow(new EtagMismatchException("etag changed"))
                .when(documentIngestionOrchestrator).processQueueMessage(any(), any());

        // when
        assertDoesNotThrow(() -> documentIngestionFunction.run(queueMessage(metadata()), 1));

        // then
        verify(documentIngestionOrchestrator, never()).processQueueMessageFailed(any(), any());
        verify(documentIngestionOrchestrator, never()).processQueueMessageFailedIfSafe(any(), any());
        // the guard skips releasing an already-stale lease (a release would be a guaranteed 412)
        verify(outcomeTableService, never()).releaseLease(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Throw DocumentProcessingException and release the lease when Orchestrator fails with budget remaining")
    void shouldThrowDocumentProcessingExceptionWhenOrchestratorFails() throws Exception {
        // given
        stubClaimableRow();
        final DocumentProcessingException orchestratorException = new DocumentProcessingException("Orchestrator failed");
        doThrow(orchestratorException).when(documentIngestionOrchestrator).processQueueMessage(any(), any());

        // when & then
        final DocumentProcessingException exception = assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage(metadata()), 1));
        assertEquals("Error processing queueMessage", exception.getMessage());
        verify(outcomeTableService).releaseLease(null, DOCUMENT_ID, CLAIM_ETAG);
    }

    @Test
    @DisplayName("Update DocumentIngestion failed (fenced) when Orchestrator fails and all retry attempts exhausted")
    void shouldUpdatedDocumentIngestionFailedWhenThrowsDocumentProcessingExceptionAndRetryAttemptsExhausted() throws Exception {
        // given
        final QueueIngestionMetadata metadata = metadata();
        stubClaimableRow();
        final DocumentProcessingException orchestratorException = new DocumentProcessingException("Orchestrator failed");
        doThrow(orchestratorException).when(documentIngestionOrchestrator).processQueueMessage(any(), any());

        // when
        documentIngestionFunction.run(queueMessage(metadata), 3);

        // then
        verify(documentIngestionOrchestrator).processQueueMessageFailed(metadata, new ClaimToken(null, DOCUMENT_ID, CLAIM_ETAG));
    }

    @Test
    @DisplayName("Throw JsonProcessingException and log error when Fails")
    void shouldLogAndNotThrowWhenJsonDeserializationFails() throws Exception {
        final String queueMessage = "{}";
        try (MockedStatic<ObjectMapperFactory> mocked = mockStatic(ObjectMapperFactory.class)) {
            final ObjectMapper objectMapper = mock(ObjectMapper.class);
            mocked.when(ObjectMapperFactory::getObjectMapper).thenReturn(objectMapper);
            when(objectMapper.readValue(queueMessage, QueueIngestionMetadata.class))
                    .thenThrow(new JsonProcessingException("Invalid JSON") {
                    });

            assertDoesNotThrow(() -> documentIngestionFunction.run(queueMessage, 1L));

            verifyNoInteractions(documentIngestionOrchestrator);
            verifyNoInteractions(outcomeTableService);
        }
    }

}
