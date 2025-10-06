package uk.gov.moj.cp.metadata.check.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import com.microsoft.azure.functions.OutputBinding;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionOrchestratorServiceTest {

    @Mock
    private DocumentMetadataService documentMetadataService;

    @Mock
    private OutputBinding<String> successMessage;

    @Mock
    private OutputBinding<DocumentIngestionOutcome> failureOutcome;

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
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("content_type", "application/pdf");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName, successMessage, failureOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(successMessage).setValue(anyString());
        verify(failureOutcome, never()).setValue(any()); // No failure outcome for success
    }

    @Test
    @DisplayName("Handle Blob Not Found Exception")
    void shouldHandleBlobNotFoundException() {
        // given
        String documentName = "nonexistent.pdf";
        String documentId = null; // No documentId for blob not found
        DocumentIngestionOutcome expectedOutcome = new DocumentIngestionOutcome();
        expectedOutcome.setDocumentName(documentName);
        expectedOutcome.setStatus("INVALID_METADATA");
        expectedOutcome.setReason("Invalid or incomplete nested metadata detected");

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));
        when(documentMetadataService.createInvalidMetadataOutcome(documentName, documentId)).thenReturn(expectedOutcome);

        // when
        ingestionOrchestratorService.processDocument(documentName, successMessage, failureOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(documentMetadataService).createInvalidMetadataOutcome(documentName, documentId);
        verify(successMessage, never()).setValue(anyString());
        verify(failureOutcome).setValue(any(DocumentIngestionOutcome.class));
    }

    @Test
    @DisplayName("Handle Invalid Metadata Exception")
    void shouldHandleInvalidMetadataException() {
        // given
        String documentName = "invalid.pdf";
        String documentId = null; // documentId will be null for invalid metadata
        DocumentIngestionOutcome expectedOutcome = new DocumentIngestionOutcome();
        expectedOutcome.setDocumentName(documentName);
        expectedOutcome.setStatus("INVALID_METADATA");
        expectedOutcome.setReason("Invalid or incomplete nested metadata detected");

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName));
        when(documentMetadataService.createInvalidMetadataOutcome(documentName, documentId)).thenReturn(expectedOutcome);

        // when
        ingestionOrchestratorService.processDocument(documentName, successMessage, failureOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(documentMetadataService).createInvalidMetadataOutcome(documentName, documentId);
        verify(successMessage, never()).setValue(anyString());
        verify(failureOutcome).setValue(any(DocumentIngestionOutcome.class));
    }

    @Test
    @DisplayName("Handle General Exception")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";
        String documentId = "123e4567-e89b-12d3-a456-426614174000";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new RuntimeException("Connection failed"));

        // when
        ingestionOrchestratorService.processDocument(documentName, successMessage, failureOutcome);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(successMessage, never()).setValue(anyString());
        verify(failureOutcome).setValue(any(DocumentIngestionOutcome.class));
    }
}
