package uk.gov.moj.cp.metadata.check;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.ai.util.EnvVarUtil;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DocumentBlobTriggerFunctionTest {

    private final String blobName = "123_20260226.json";
    private final String documentId = "123";

    private BlobClientService blobClientService;
    private DocumentUploadService documentUploadService;
    private OutputBinding<String> outputBinding;

    private DocumentBlobTriggerFunction function;

    @BeforeEach
    void setUp() {
        blobClientService = mock(BlobClientService.class);
        documentUploadService = mock(DocumentUploadService.class);
        outputBinding = mock(OutputBinding.class);

        function = new DocumentBlobTriggerFunction(blobClientService, documentUploadService);
    }

    @Test
    void shouldReturnEarly_whenBlobIsNotAvailable() {
        when(blobClientService.isBlobAvailable(blobName)).thenReturn(false);

        function.run(new byte[]{}, blobName, outputBinding);

        verify(blobClientService).isBlobAvailable(blobName);
        verifyNoInteractions(documentUploadService);
        verify(outputBinding, never()).setValue(any());
    }

    @Test
    void shouldProcessAndPublishMessage_whenBlobIsAvailable() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class)) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT)).thenReturn("http://blob.web.com/");
            mockedEnvVarUtil.when(() -> getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD)).thenReturn("doc-upload");

            when(blobClientService.isBlobAvailable(blobName)).thenReturn(true);

            DocumentIngestionOutcome document = mock(DocumentIngestionOutcome.class);
            when(document.getDocumentId()).thenReturn(documentId);
            when(document.getDocumentName()).thenReturn("doc.json");
            when(document.getMetadata()).thenReturn("{\"version\":\"1.0\"}");

            when(documentUploadService.getDocument(documentId)).thenReturn(document);

            function.run(new byte[]{}, blobName, outputBinding);

            verify(documentUploadService).getDocument(documentId);
            verify(documentUploadService).updateDocumentAwaitingIngestion(documentId, "doc.json");

            verify(outputBinding).setValue(anyString());
        }
    }

    @Test
    void shouldThrowIllegalStateException() {
        when(blobClientService.isBlobAvailable(blobName)).thenReturn(true);

        final DocumentIngestionOutcome document = mock(DocumentIngestionOutcome.class);
        when(document.getDocumentId()).thenReturn(documentId);
        when(document.getDocumentName()).thenReturn("doc.pdf");

        // invalid JSON to trigger stringToMap failure
        when(document.getMetadata()).thenReturn("invalid-json");

        when(documentUploadService.getDocument(documentId)).thenReturn(document);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> function.run(new byte[]{}, blobName, outputBinding));

        assertTrue(exception.getMessage().contains("Unable to serialize message"));
    }
}