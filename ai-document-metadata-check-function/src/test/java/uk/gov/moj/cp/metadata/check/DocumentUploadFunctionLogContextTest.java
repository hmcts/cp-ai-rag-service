package uk.gov.moj.cp.metadata.check;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DocumentUploadFunctionLogContextTest {

    @SuppressWarnings("unchecked")
    private final HttpRequestMessage<DocumentUploadRequest> request = mock(HttpRequestMessage.class);
    private final HttpResponseMessage.Builder responseBuilder = mock(HttpResponseMessage.Builder.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final BlobClientService blobClientService = mock(BlobClientService.class);
    private final DocumentUploadService documentUploadService = mock(DocumentUploadService.class);
    private final DocumentBlobNameResolver blobNameResolver = mock(DocumentBlobNameResolver.class);

    private DocumentUploadFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-upload");
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
        function = new DocumentUploadFunction(blobClientService, documentUploadService, blobNameResolver, null);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("the caller-supplied document id is on the thread once validated, and gone after run")
    void documentIdIsOnThreadAfterValidation() throws Exception {
        final String documentId = randomUUID().toString();
        final MetadataFilter filter = new MetadataFilter();
        filter.setKey("document_id");
        filter.setValue(randomUUID().toString());
        when(request.getBody()).thenReturn(new DocumentUploadRequest(documentId, "test.pdf", List.of(filter)));
        when(documentUploadService.isDocumentAlreadyProcessed(null, documentId)).thenReturn(false);
        when(blobNameResolver.getBlobName(anyString(), anyString())).thenReturn("blob");
        when(blobClientService.getSasUrl(anyString(), anyInt())).thenReturn("http://sas");

        final AtomicReference<String> docOnThread = new AtomicReference<>();
        doAnswer(invocation -> {
            docOnThread.set(MDC.get(DOCUMENT_ID));
            return null;
        }).when(documentUploadService).addDocumentAwaitingUpload(any(), anyString(), anyString(), anyMap(), anyString());

        function.run(request, context);

        assertEquals(documentId, docOnThread.get());
        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }
}
