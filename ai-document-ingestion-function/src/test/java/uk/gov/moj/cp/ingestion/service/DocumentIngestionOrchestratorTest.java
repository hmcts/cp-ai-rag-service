package uk.gov.moj.cp.ingestion.service;

import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

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
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "Contract-Agreement.pdf",
                singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Contract-Agreement.pdf",
                "2025-10-07T10:30:45.123456Z",
                false
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
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                singletonMap("document_type", "MCC"),
                "https://storage.blob.core.windows.net/container/Burglary-IDPC.pdf",
                "2025-10-06T05:14:39.658828Z",
                false
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
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "456e7890-f123-4567-8901-234567890123",
                "Simple-Document.pdf",
                Collections.emptyMap(),
                "https://storage.blob.core.windows.net/container/Simple-Document.pdf",
                "2025-10-07T12:00:00.000000Z",
                false
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
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "789e0123-f456-7890-abcd-ef1234567890",
                "Legal-Contract-Agreement.pdf",
                singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Legal-Contract-Agreement.pdf",
                "2025-10-07T15:45:30.987654Z",
                false
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

    @Test
    @DisplayName("when Document having previous versions should mark them inActive in Search")
    void shouldMarkSupersededDocumentInactive() throws Exception {
        // given
        final String documentId = "789e0123-f456-7890-abcd-ef1234567890";
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                documentId,
                "Legal-Contract-Agreement.pdf",
                singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Legal-Contract-Agreement.pdf",
                "2025-10-07T15:45:30.987654Z",
                true
        );

        final DocumentIngestionOutcome documentIngestionOutcome = mock(DocumentIngestionOutcome.class);
        when(documentIngestionOutcome.getSupersededDocuments()).thenReturn("67d6aeac-c533-41aa-9824-cc4ef7a346ef,569fefd2-d032-4c01-be59-b9045de5f43a");
        when(documentIngestionOutcomeTableService.getDocumentById(documentId)).thenReturn(documentIngestionOutcome);
        doNothing().when(documentIngestionOutcomeTableService).upsertDocument(eq("789e0123-f456-7890-abcd-ef1234567890"), eq("INGESTION_SUCCESS"), eq("Document ingestion completed successfully"));

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentStorageService).markDocumentsInActive(any());
        verify(documentIngestionOutcomeTableService).upsertDocument(
                documentId,
                "INGESTION_SUCCESS",
                "Document ingestion completed successfully");
    }

    @Test
    @DisplayName("Document has previous versions and mark superseded document inactive failed")
    void shouldThrowExceptionWhenFailedToMarkSupersededDocumentInactive() throws Exception {
        // given
        final String documentId = "789e0123-f456-7890-abcd-ef1234567890";
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                documentId,
                "Legal-Contract-Agreement.pdf",
                singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Legal-Contract-Agreement.pdf",
                "2025-10-07T15:45:30.987654Z",
                true
        );

        doThrow(new EntityRetrievalException("DB write error!"))
                .when(documentIngestionOutcomeTableService).getDocumentById(documentId);

        // when
        final DocumentProcessingException ex = assertThrows(
                DocumentProcessingException.class, () -> orchestrator.processQueueMessage(metadata));

        assertThat(ex.getMessage().contains("Unable to mark documents as Inactive in search index which were to be superseded by document with ID: " + documentId), is(true));
        verify(documentIngestionOutcomeTableService, times(0)).upsertDocument(anyString(), anyString(), anyString());
    }


    @Test
    @DisplayName("Handle Queue Message when isDocumentIdAsRowKey true")
    void shouldHandleQueueMessageWhenDocumentIdUsedAsRowKey() throws Exception {
        // given
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "789e0123-f456-7890-abcd-ef1234567890",
                "Legal-Contract-Agreement.pdf",
                singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/legal/Legal-Contract-Agreement.pdf",
                "2025-10-07T15:45:30.987654Z",
                true
        );

        doNothing().when(documentIngestionOutcomeTableService).upsertDocument(anyString(), anyString(), anyString());

        // when
        orchestrator.processQueueMessage(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertDocument(
                "789e0123-f456-7890-abcd-ef1234567890",
                "INGESTION_SUCCESS",
                "Document ingestion completed successfully");
    }

    @Test
    @DisplayName("Handle Queue Message processing fail and isDocumentIdAsRowKey true")
    void shouldHandleQueueMessageProcessingFailWhenDocumentIdUsedAsRowKey() throws Exception {
        //given
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString(),
                true
        );

        // when
        orchestrator.processQueueMessageFailed(metadata);

        // then
        verify(documentIngestionOutcomeTableService).upsertDocument(
                "123e4567-e89b-12d3-a456-426614174000",
                "INGESTION_FAILED",
                "Document ingestion failed during processing");
    }

    @Test
    @DisplayName("Handle Queue Message processing failed and fail to update the table")
    void shouldHandleQueueMessageProcessingFailedAndFailToUpdateTable() {
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString(),
                true
        );

        doThrow(new RuntimeException("DB write error!"))
                .when(documentIngestionOutcomeTableService).upsertDocument(any(), any(), any());

        orchestrator.processQueueMessageFailed(metadata);
    }
}