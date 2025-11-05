package uk.gov.moj.cp.metadata.check.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.util.HashMap;
import java.util.Map;

import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionOrchestratorServiceTest {

    @Mock
    private DocumentMetadataService documentMetadataService;

    @Mock
    private TableStorageService tableStorageService;

    @Mock
    private OutputBinding<String> queueMessage;

    @Mock
    private OutputBinding<?> messageOutcome;

    private IngestionOrchestratorService ingestionOrchestratorService;

    @BeforeEach
    void setUp() {
        ingestionOrchestratorService = new IngestionOrchestratorService(documentMetadataService, tableStorageService);
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessDocumentSuccessfully() {
        // given
        String documentName = "test.pdf";
        String documentId = "123e4567-e89b-12d3-a456-426614174000";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", documentId);
        metadata.put("content_type", "application/pdf");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage).setValue(anyString());
        verify(tableStorageService).upsertDocumentOutcome(documentName, documentId, "METADATA_VALIDATED", "Document metadata validated and sent to queue");
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception")
    void shouldHandleMetadataValidationException() {
        // given
        String documentName = "nonexistent.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).upsertDocumentOutcome(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);
    }

    @Test
    @DisplayName("Handle Invalid Metadata Exception")
    void shouldHandleInvalidMetadataException() {
        // given
        String documentName = "invalid.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).upsertDocumentOutcome(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Invalid metadata: Missing document ID: " + documentName);
    }

    @Test
    @DisplayName("Handle General Exception")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new RuntimeException("Connection failed"));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).upsertDocumentOutcome(documentName, "UNKNOWN_DOCUMENT", "QUEUE_FAILED", "Connection failed");
    }
}
