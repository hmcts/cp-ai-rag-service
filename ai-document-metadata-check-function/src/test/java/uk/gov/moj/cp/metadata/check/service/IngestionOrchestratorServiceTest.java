package uk.gov.moj.cp.metadata.check.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
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
    private OutputBinding<String> queueMessage;

    @Mock
    private OutputBinding<DocumentIngestionOutcome> messageOutcome;

    private IngestionOrchestratorService ingestionOrchestratorService;

    @BeforeEach
    void setUp() {
        ingestionOrchestratorService = new IngestionOrchestratorService(documentMetadataService);
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
        verify(messageOutcome).setValue(any(DocumentIngestionOutcome.class)); // Success outcome should be recorded
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception")
    void shouldHandleMetadataValidationException() {
        // given
        String documentName = "nonexistent.pdf";
        String documentId = null;

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(messageOutcome).setValue(any(DocumentIngestionOutcome.class)); // Failure outcome should be recorded
    }

    @Test
    @DisplayName("Handle Invalid Metadata Exception")
    void shouldHandleInvalidMetadataException() {
        // given
        String documentName = "invalid.pdf";
        String documentId = null;

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(messageOutcome).setValue(any(DocumentIngestionOutcome.class)); // Failure outcome should be recorded
    }

    @Test
    @DisplayName("Handle General Exception")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";
        String documentId = null;

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new RuntimeException("Connection failed"));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage, messageOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(messageOutcome).setValue(any(DocumentIngestionOutcome.class)); // Failure outcome should be recorded
    }
}
