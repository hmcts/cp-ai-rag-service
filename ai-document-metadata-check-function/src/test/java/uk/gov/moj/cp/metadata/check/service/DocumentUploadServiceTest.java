package uk.gov.moj.cp.metadata.check.service;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.AWAITING_UPLOAD;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.FILE_SIZE_OVER_LIMIT;
import static uk.gov.moj.cp.metadata.check.service.DocumentUploadService.AWAITING_UPLOAD_REASON;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.DataRetrievalException;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DocumentUploadServiceTest {

    @Mock
    private DocumentIngestionOutcomeTableService tableService;

    private DocumentUploadService documentUploadService;

    @BeforeEach
    void setUp() {
        documentUploadService = new DocumentUploadService(tableService);
    }

    @Test
    void shouldReturnTrue_whenDocumentExists() throws Exception {
        final String documentId = "doc-123";

        final DocumentIngestionOutcome outcome = mock(DocumentIngestionOutcome.class);
        when(outcome.getStatus()).thenReturn("COMPLETED");
        when(tableService.getDocumentById(null, documentId)).thenReturn(outcome);

        final boolean result = documentUploadService.isDocumentAlreadyProcessed(null, documentId);

        assertThat(result, is(true));
        verify(tableService).getDocumentById(null, documentId);
    }

    @Test
    void shouldReturnFalse_whenDocumentDoesNotExist() throws Exception {
        final String documentId = "doc-123";

        when(tableService.getDocumentById(null, documentId)).thenReturn(null);

        final boolean result = documentUploadService.isDocumentAlreadyProcessed(null, documentId);

        assertThat(result, is(false));
        verify(tableService).getDocumentById(null, documentId);
    }

    @Test
    void shouldThrowDataRetrievalException_whenEntityRetrievalFails() throws Exception {
        final String documentId = "doc-123";

        when(tableService.getDocumentById(null, documentId)).thenThrow(new EntityRetrievalException("error"));

        assertThrows(DataRetrievalException.class, () -> documentUploadService.isDocumentAlreadyProcessed(null, documentId));
    }

    @Test
    void shouldCallInsert_whenNoDuplicate() throws Exception {
        final String documentId = "doc-123";
        final String documentName = "TestDoc";
        final Map<String, String> metadataMap = Map.of("k1", "v1");
        final String supersededDocuments = "doc1,doc2";

        documentUploadService.addDocumentAwaitingUpload(null, documentId, documentName, metadataMap, supersededDocuments);

        verify(tableService).insert(isNull(), eq(documentId), eq(documentName), eq("{\"k1\":\"v1\"}"), eq(supersededDocuments), eq(AWAITING_UPLOAD.name()), eq(AWAITING_UPLOAD_REASON));
    }

    @Test
    void shouldCallUpsert_whenDocumentSizeExceedConfiguredSizeLimit() throws Exception {
        final String documentId = "doc-123";
        final long documentSize = 1500L;
        final long maxFileSize = 1200L;

        documentUploadService.updateDocumentFileSizeOverLimit(null, documentId, documentSize, maxFileSize);

        verify(tableService).upsertDocument(null, documentId, FILE_SIZE_OVER_LIMIT.name(), "Document Uploaded with size=1500 is over the configured size limit=1200");
    }

    @Test
    void shouldThrow_whenDuplicateRecordExceptionOccurs() throws Exception {
        final String documentId = "doc-123";
        final String documentName = "TestDoc";
        final Map<String, String> metadataMap = Map.of("k1", "v1");
        final String supersededDocuments = "doc1,doc2";

        doThrow(new DuplicateRecordException("duplicate")).when(tableService).insert(any(), any(), any(), any(), any(), any(), any());

        assertThrows(DuplicateRecordException.class, () -> documentUploadService.addDocumentAwaitingUpload(null, documentId, documentName, metadataMap, supersededDocuments));

        verify(tableService).insert(isNull(), eq(documentId), eq(documentName), anyString(), anyString(),anyString(), anyString());
    }

    @Test
    void shouldScopeDedupLookupToTheSuppliedClient() throws Exception {
        final String clientId = "11111111-1111-1111-1111-111111111111";
        final String documentId = "doc-123";
        final DocumentIngestionOutcome outcome = mock(DocumentIngestionOutcome.class);
        when(tableService.getDocumentById(clientId, documentId)).thenReturn(outcome);

        final boolean result = documentUploadService.isDocumentAlreadyProcessed(clientId, documentId);

        assertThat(result, is(true));
        verify(tableService).getDocumentById(clientId, documentId);
    }
}