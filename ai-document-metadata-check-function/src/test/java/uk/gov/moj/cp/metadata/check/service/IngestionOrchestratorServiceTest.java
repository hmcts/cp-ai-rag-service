package uk.gov.moj.cp.metadata.check.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.model.DocumentStatus.METADATA_VALIDATED;

import uk.gov.moj.cp.ai.FunctionEnvironment;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.util.HashMap;
import java.util.Map;

import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionOrchestratorServiceTest {

    private DocumentMetadataService documentMetadataService;

    private DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    @Mock
    private OutputBinding<String> queueMessage;

    @Mock
    private OutputBinding<?> messageOutcome;

    private IngestionOrchestratorService ingestionOrchestratorService;

    private static final String ENDPOINT = "https://example-endpoint.com";

    @BeforeEach
    void setUp() {
        try (MockedStatic<FunctionEnvironment> mocked = Mockito.mockStatic(FunctionEnvironment.class)) {
            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            mocked.when(FunctionEnvironment::get).thenReturn(mockEnv);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);

            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);
            when(mockStorageConfig.blobEndpoint()).thenReturn(ENDPOINT);

            documentMetadataService = mock(DocumentMetadataService.class);
            documentIngestionOutcomeTableService = mock(DocumentIngestionOutcomeTableService.class);
            ingestionOrchestratorService = new IngestionOrchestratorService(documentMetadataService, documentIngestionOutcomeTableService);
        }
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
                .when(documentIngestionOutcomeTableService).insertIntoTable(documentName, documentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());


        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(documentIngestionOutcomeTableService).insertIntoTable(documentName, documentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());
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
        verify(documentIngestionOutcomeTableService).insertIntoTable(documentName, documentId, "METADATA_VALIDATED", "Document metadata validated and sent to queue");
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
        verify(documentIngestionOutcomeTableService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);
    }

    @Test
    @DisplayName("Handle Metadata Validation Exception and duplicate record insertion")
    void shouldHandleMetadataValidationExceptionAlongWithDuplicateInsertionOfData() throws MetadataValidationException, DuplicateRecordException {
        // given
        String documentName = "nonexistent.pdf";

        when(documentMetadataService.processDocumentMetadata(documentName))
                .thenThrow(new MetadataValidationException("Blob not found: " + documentName));

        doThrow(new DuplicateRecordException("Duplicate record found for document: " + documentName)).when(documentIngestionOutcomeTableService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentMetadataService).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(documentIngestionOutcomeTableService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Blob not found: " + documentName);
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
        verify(documentIngestionOutcomeTableService).getFirstDocumentMatching(documentName);
        verify(documentIngestionOutcomeTableService).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "INVALID_METADATA", "Invalid metadata: Missing document ID: " + documentName);
    }

    @Test
    @DisplayName("Handle processing of blob seen idempotently")
    void shouldHandleDuplicateBlobInvocationIdempotently() throws MetadataValidationException, DuplicateRecordException, EntityRetrievalException {
        // given
        String documentName = "test.pdf";

        when(documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName)).thenReturn(new DocumentIngestionOutcome());

        // when
        ingestionOrchestratorService.processDocument(documentName, queueMessage);

        // then
        verify(documentIngestionOutcomeTableService).getFirstDocumentMatching(documentName);
        verify(documentMetadataService, never()).processDocumentMetadata(documentName);
        verify(queueMessage, never()).setValue(anyString());
        verify(documentIngestionOutcomeTableService, never()).insertIntoTable(documentName, "UNKNOWN_DOCUMENT", "QUEUE_FAILED", "Connection failed");
    }
}
