package uk.gov.moj.cp.metadata.check;

import static java.time.ZonedDateTime.now;
import static java.time.ZonedDateTime.parse;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.ai.util.EnvVarUtil;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class DocumentBlobTriggerFunctionTest {

    private final String blobName = "123_20260226.json";
    private final String documentId = "123";

    private BlobClientService blobClientService;
    private DocumentUploadService documentUploadService;
    private OutputBinding<String> outputBinding;
    private DocumentBlobNameResolver documentBlobNameResolver;
    private DocumentBlobTriggerFunction function;

    @BeforeEach
    void setUp() {
        blobClientService = mock(BlobClientService.class);
        documentUploadService = mock(DocumentUploadService.class);
        outputBinding = mock(OutputBinding.class);
        documentBlobNameResolver = mock(DocumentBlobNameResolver.class);
        function = new DocumentBlobTriggerFunction(blobClientService, documentUploadService, documentBlobNameResolver);
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
    void shouldProcessAndPublishMessage_whenBlobIsAvailable() throws JsonProcessingException {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class)) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT)).thenReturn("http://blob.web.com/");
            mockedEnvVarUtil.when(() -> getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD)).thenReturn("doc-upload");

            when(documentBlobNameResolver.getDocumentId(blobName)).thenReturn(documentId);
            when(blobClientService.isBlobAvailable(blobName)).thenReturn(true);

            DocumentIngestionOutcome document = mock(DocumentIngestionOutcome.class);
            when(document.getDocumentId()).thenReturn(documentId);
            when(document.getDocumentName()).thenReturn("doc.json");
            when(document.getMetadata()).thenReturn("{\"version\":\"1.0\"}");

            when(documentUploadService.getDocument(documentId)).thenReturn(document);

            function.run(new byte[]{}, blobName, outputBinding);

            verify(documentUploadService).getDocument(documentId);
            verify(documentUploadService).updateDocumentAwaitingIngestion(documentId);

            final ArgumentCaptor<String> queueMessageCaptor = ArgumentCaptor.forClass(String.class);
            verify(outputBinding).setValue(queueMessageCaptor.capture());
            final QueueIngestionMetadata queueIngestionMetadata = getObjectMapper()
                    .readValue(queueMessageCaptor.getValue(), QueueIngestionMetadata.class);

            //assert metadata
            assertThat(queueIngestionMetadata.documentName(), is("doc.json"));
            assertThat(queueIngestionMetadata.documentId(), is("123"));
            assertThat(queueIngestionMetadata.metadata().get("document_id"), is("123"));
            assertThat(queueIngestionMetadata.metadata().get("documentId"), is("123"));
            assertThat(queueIngestionMetadata.metadata().get("version"), is("1.0"));
            assertThat(queueIngestionMetadata.blobUrl(), is("http://blob.web.com/doc-upload/123_20260226.json"));
            assertThat(parse(queueIngestionMetadata.currentTimestamp()).isBefore(now()), is(true));
        }
    }

    @Test
    void shouldThrowIllegalStateException() {
        when(blobClientService.isBlobAvailable(blobName)).thenReturn(true);

        final DocumentIngestionOutcome document = mock(DocumentIngestionOutcome.class);
        when(document.getDocumentId()).thenReturn(documentId);
        when(document.getDocumentName()).thenReturn("doc.pdf");
        when(documentBlobNameResolver.getDocumentId(blobName)).thenReturn(documentId);

        // invalid JSON to trigger stringToMap failure
        when(document.getMetadata()).thenReturn("invalid-json");

        when(documentUploadService.getDocument(documentId)).thenReturn(document);

        final IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> function.run(new byte[]{}, blobName, outputBinding));

        assertTrue(exception.getMessage().contains("Unable to serialize message"));
    }
}