package uk.gov.moj.cp.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.idempotency.IdempotencyGuard;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
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
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Enforcement wiring for the ingestion worker: the clientId carried on the ingestion queue message
 * is used to claim the idempotency lease, so the claim token handed to the orchestrator is scoped
 * to the message's client namespace on every delivery.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DOCUMENT_ID = "53ac8b90-c4c8-472c-a5ee-fe84ed96047b";
    private static final String READ_ETAG = "W/\"read\"";
    private static final String CLAIM_ETAG = "W/\"claimed\"";

    @Mock
    private DocumentIngestionOrchestrator orchestrator;
    @Mock
    private DocumentIngestionOutcomeTableService outcomeTableService;

    private DocumentIngestionFunction function;

    @BeforeEach
    void setUp() {
        function = new DocumentIngestionFunction(orchestrator, new IdempotencyGuard(outcomeTableService, Duration.ofMinutes(10)));
    }

    private void stubClaimableRow() throws Exception {
        when(outcomeTableService.readForClaim(any(), eq(DOCUMENT_ID)))
                .thenReturn(new LeaseSnapshot("AWAITING_INGESTION", READ_ETAG, null, null));
        when(outcomeTableService.isTerminal("AWAITING_INGESTION")).thenReturn(false);
        when(outcomeTableService.claimLease(any(), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class)))
                .thenReturn(CLAIM_ETAG);
    }

    private QueueIngestionMetadata metadata(final String clientId) {
        return new QueueIngestionMetadata(DOCUMENT_ID, "doc.pdf",
                Map.of("document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/doc.pdf",
                Instant.now().toString(), clientId);
    }

    private String queueMessage(final QueueIngestionMetadata metadata) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(metadata);
    }

    @Test
    @DisplayName("claims the lease on the message's client namespace and hands a client-scoped token to the orchestrator")
    void shouldClaimAndProcessUnderMessageClientId() throws Exception {
        final QueueIngestionMetadata metadata = metadata(CLIENT_ID);
        stubClaimableRow();

        function.run(queueMessage(metadata), 1);

        verify(outcomeTableService).claimLease(eq(CLIENT_ID), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class));
        verify(orchestrator).processQueueMessage(metadata, new ClaimToken(CLIENT_ID, DOCUMENT_ID, CLAIM_ETAG));
    }

    @Test
    @DisplayName("legacy message without a client id keeps the null-scoped claim token")
    void shouldClaimWithoutScope_whenLegacyMessage() throws Exception {
        final QueueIngestionMetadata metadata = metadata(null);
        stubClaimableRow();

        function.run(queueMessage(metadata), 1);

        verify(outcomeTableService).claimLease(isNull(), eq(DOCUMENT_ID), eq(READ_ETAG), anyString(), any(OffsetDateTime.class));
        verify(orchestrator).processQueueMessage(metadata, new ClaimToken(null, DOCUMENT_ID, CLAIM_ETAG));
    }
}
