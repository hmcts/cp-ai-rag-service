package uk.gov.moj.cp.metadata.check.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.model.DocumentStatus.METADATA_VALIDATED;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
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
    @DisplayName("Stop further processing of document if record already exists in status table")
    void shouldStopProcessingDocumentIdDuplicateEntryFoundInStatusTable() throws MetadataValidationException, DuplicateRecordException {
        // given
        String documentName = "test.pdf";
        String documentId = "123e4567-e89b-12d3-a456-426614174000";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", documentId);
        metadata.put("content_type", "application/pdf");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);
        doThrow(new DuplicateRecordException("Duplicate record found for document: " + documentName))
                .when(tableStorageService).insertIntoTable(documentName, documentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());


        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(tableStorageService).insertIntoTable(documentName, documentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());
        verify(queueMessage, never()).setValue(anyString());
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessDocumentSuccessfully() throws MetadataValidationException, DuplicateRecordException {
        // given
        String documentName = "test.pdf";
        String documentId = "123e4567-e89b-12d3-a456-426614174000";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", documentId);
        metadata.put("content_type", "application/pdf");

        when(documentMetadataService.processDocumentMetadata(documentName)).thenReturn(metadata);

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage).setValue(anyString());
        verify(tableStorageService).insertIntoTable(documentName, documentId, "METADATA_VALIDATED", "Document metadata validated and sent to queue");
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception")
    void shouldHandleMetadataValidationException() throws MetadataValidationException, DuplicateRecordException {
        // given
        String documentName = "nonexistent.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception and duplicate record insertion")
    void shouldHandleMetadataValidationExceptionAlongWithDuplicateInsertionOfData() throws MetadataValidationException, DuplicateRecordException {
        // given
        String documentName = "nonexistent.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));

        doThrow(new DuplicateRecordException("Duplicate record found for document: " + documentName)).when(tableStorageService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);
    }

    @Test
    @DisplayName("Handle Invalid Metadata Exception")
    void shouldHandleInvalidMetadataException() throws MetadataValidationException, DuplicateRecordException, EntityRetrievalException {
        // given
        String documentName = "invalid.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName));

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService).getFirstDocumentMatching(documentName);
        verify(tableStorageService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Invalid metadata: Missing document ID: " + documentName);
    }

    @Test
    @DisplayName("Handle processing of blob seen idempotently")
    void shouldHandleDuplicateBlobInvocationIdempotently() throws MetadataValidationException, DuplicateRecordException, EntityRetrievalException {
        // given
        String documentName = "test.pdf";

        when(tableStorageService.getFirstDocumentMatching(documentName)).thenReturn(new DocumentIngestionOutcome());

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(tableStorageService).getFirstDocumentMatching(documentName);
        verify(documentMetadataService, never()).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(tableStorageService, never()).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "QUEUE_FAILED", "Connection failed");
    }
}
