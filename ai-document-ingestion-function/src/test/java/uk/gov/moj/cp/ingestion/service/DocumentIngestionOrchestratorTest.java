package uk.gov.moj.cp.ingestion.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionOrchestratorTest {

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

    @Test
    @DisplayName("Process Queue Message Successfully")
    void shouldProcessQueueMessageSuccessfully() throws Exception {
        // given
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "Contract-Agreement.pdf",
                Collections.singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Contract-Agreement.pdf",
                "2025-10-07T10:30:45.123456Z"
        );

        doNothing().when(documentIngestionOutcomeTableService).upsertIntoTable(anyString(), anyString(), anyString(), anyString());

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertIntoTable("Contract-Agreement.pdf", "123e4567-e89b-12d3-a456-426614174000", "INGESTION_SUCCESS", "Document ingestion completed successfully");
    }

    @Test
    @DisplayName("Process Queue Message with Different Document Types")
    void shouldProcessQueueMessageWithDifferentDocumentTypes() throws Exception {
        // given
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Collections.singletonMap("document_type", "MCC"),
                "https://storage.blob.core.windows.net/container/Burglary-IDPC.pdf",
                "2025-10-06T05:14:39.658828Z"
        );

        doNothing().when(documentIngestionOutcomeTableService).upsertIntoTable(anyString(), anyString(), anyString(), anyString());

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertIntoTable("Burglary-IDPC.pdf", "53ac8b90-c4c8-472c-a5ee-fe84ed96047b", "INGESTION_SUCCESS", "Document ingestion completed successfully");
    }

    @Test
    @DisplayName("Handle Queue Message with Empty Metadata")
    void shouldHandleQueueMessageWithEmptyMetadata() throws Exception {
        // given
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "456e7890-f123-4567-8901-234567890123",
                "Simple-Document.pdf",
                Collections.emptyMap(),
                "https://storage.blob.core.windows.net/container/Simple-Document.pdf",
                "2025-10-07T12:00:00.000000Z"
        );

        doNothing().when(documentIngestionOutcomeTableService).upsertIntoTable(anyString(), anyString(), anyString(), anyString());

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertIntoTable("Simple-Document.pdf", "456e7890-f123-4567-8901-234567890123", "INGESTION_SUCCESS", "Document ingestion completed successfully");
    }

    @Test
    @DisplayName("Handle Queue Message with Complex Metadata")
    void shouldHandleQueueMessageWithComplexMetadata() throws Exception {
        // given
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "789e0123-f456-7890-abcd-ef1234567890",
                "Legal-Contract-Agreement.pdf",
                Collections.singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Legal-Contract-Agreement.pdf",
                "2025-10-07T15:45:30.987654Z"
        );

        doNothing().when(documentIngestionOutcomeTableService).upsertIntoTable(anyString(), anyString(), anyString(), anyString());

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertIntoTable("Legal-Contract-Agreement.pdf",
                "789e0123-f456-7890-abcd-ef1234567890",
                "INGESTION_SUCCESS",
                "Document ingestion completed successfully");
    }
}