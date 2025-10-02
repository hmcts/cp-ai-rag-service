package uk.gov.moj.cp.metadata.check.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;
import uk.gov.moj.cp.metadata.check.exception.QueueSendException;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionOrchestratorServiceTest {

    @Mock
    private DocumentMetadataService documentMetadataService;

    @Mock
    private QueueStorageService queueStorageService;

    @Mock
    private TableStorageService tableStorageService;

    private IngestionOrchestratorService ingestionOrchestratorService;

    @BeforeEach
    void setUp() {
        ingestionOrchestratorService = new IngestionOrchestratorService(
                documentMetadataService, queueStorageService, tableStorageService);
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessDocumentSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("content_type", "application/pdf");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueStorageService).sendToQueue(documentName, metadata);
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception")
    void shouldHandleMetadataValidationException() {
        // given
        String documentName = "test.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Invalid metadata"));

        // when
        ingestionOrchestratorService.processDocument(documentName);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueStorageService, never()).sendToQueue(anyString(), any());
        verify(tableStorageService, never()).recordOutcome(any());
    }

    @Test
    @DisplayName("Handle Queue Send Exception")
    void shouldHandleQueueSendException() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);
        doThrow(new QueueSendException("Queue unavailable")).when(queueStorageService).sendToQueue(documentName, metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueStorageService).sendToQueue(documentName, metadata);
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Handle Missing Document ID")
    void shouldHandleMissingDocumentId() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        // Missing document_id

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueStorageService).sendToQueue(documentName, metadata);
        verify(tableStorageService).recordOutcome(any());
    }
}
