package uk.gov.moj.cp.ingestion.service;

import static java.util.Collections.singletonMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The orchestrator's fenced outcome writes must target the same client namespace the lease was
 * claimed with — i.e. the clientId carried on the claim token, not a literal null.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionOrchestratorClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DOCUMENT_ID = "789e0123-f456-7890-abcd-ef1234567890";
    private static final ClaimToken TOKEN = new ClaimToken(CLIENT_ID, DOCUMENT_ID, "W/\"etag\"");

    @Mock
    private DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;
    @Mock
    private DocumentIntelligenceService documentIntelligenceService;
    @Mock
    private DocumentChunkingService documentChunkingService;
    @Mock
    private ChunkEmbeddingService chunkEmbeddingService;
    @Mock
    private DocumentStorageService documentStorageService;

    private DocumentIngestionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new DocumentIngestionOrchestrator(documentIngestionOutcomeTableService, documentIntelligenceService,
                documentChunkingService, chunkEmbeddingService, documentStorageService);
    }

    private QueueIngestionMetadata metadata() {
        return new QueueIngestionMetadata(DOCUMENT_ID, "doc.pdf", singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/doc.pdf", "2025-10-07T15:45:30.987654Z", CLIENT_ID);
    }

    @Test
    @DisplayName("records the success outcome under the claim token's client namespace")
    void shouldRecordSuccessOutcomeUnderTokenClientId() throws Exception {
        doNothing().when(documentIngestionOutcomeTableService).recordOutcomeFenced(any(), anyString(), anyString(), anyString(), anyString());

        orchestrator.processQueueMessage(metadata(), TOKEN);

        verify(documentIngestionOutcomeTableService).recordOutcomeFenced(
                eq(CLIENT_ID), eq(DOCUMENT_ID), eq("INGESTION_SUCCESS"), anyString(), eq(TOKEN.etag()));
    }

    @Test
    @DisplayName("records the failed outcome under the claim token's client namespace")
    void shouldRecordFailedOutcomeUnderTokenClientId() throws Exception {
        orchestrator.processQueueMessageFailed(metadata(), TOKEN);

        verify(documentIngestionOutcomeTableService).recordOutcomeFenced(
                eq(CLIENT_ID), eq(DOCUMENT_ID), eq("INGESTION_FAILED"), anyString(), eq(TOKEN.etag()));
    }
}
