package uk.gov.moj.cp.metadata.check.service;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.AWAITING_UPLOAD;
import static uk.gov.moj.cp.metadata.check.service.DocumentUploadService.AWAITING_UPLOAD_REASON;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.DataRetrievalException;
import uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer;

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
        when(tableService.getDocumentById(documentId)).thenReturn(outcome);

        final boolean result = documentUploadService.isDocumentAlreadyProcessed(documentId);

        assertThat(result, is(true));
        verify(tableService).getDocumentById(documentId);
    }

    @Test
    void shouldReturnFalse_whenDocumentDoesNotExist() throws Exception {
        final String documentId = "doc-123";

        when(tableService.getDocumentById(documentId)).thenReturn(null);

        final boolean result = documentUploadService.isDocumentAlreadyProcessed(documentId);

        assertThat(result, is(false));
        verify(tableService).getDocumentById(documentId);
    }

    @Test
    void shouldThrowDataRetrievalException_whenEntityRetrievalFails() throws Exception {
        final String documentId = "doc-123";

        when(tableService.getDocumentById(documentId)).thenThrow(new EntityRetrievalException("error"));

        assertThrows(DataRetrievalException.class, () -> documentUploadService.isDocumentAlreadyProcessed(documentId));
    }

    @Test
    void shouldCallInsert_whenNoDuplicate() throws Exception {
        final String documentId = "doc-123";
        final String documentName = "TestDoc";
        final Map<String, String> metadataMap = Map.of("k1", "v1");

        documentUploadService.recordUploadInitiated(documentId, documentName, metadataMap);

        verify(tableService).insert(eq(documentId), eq(documentName), eq("{\"k1\":\"v1\"}"), eq(AWAITING_UPLOAD.name()), eq(AWAITING_UPLOAD_REASON));
    }

    @Test
    void shouldThrow_whenDuplicateRecordExceptionOccurs() throws Exception {
        final String documentId = "doc-123";
        final String documentName = "TestDoc";
        final Map<String, String> metadataMap = Map.of("k1", "v1");

        doThrow(new DuplicateRecordException("duplicate")).when(tableService).insert(any(), any(), any(), any(), any());

        assertThrows(DuplicateRecordException.class, () -> documentUploadService.recordUploadInitiated(documentId, documentName, metadataMap));

        verify(tableService).insert(eq(documentId), eq(documentName), anyString(), anyString(), anyString());
    }
}