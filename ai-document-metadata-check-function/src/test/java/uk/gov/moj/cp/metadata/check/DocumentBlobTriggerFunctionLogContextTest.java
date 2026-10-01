package uk.gov.moj.cp.metadata.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.CLIENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The blob trigger learns both ids from the blob name. The over-limit branch is used here
 * because it exercises the resolver and a table write without needing the queue-message env vars.
 */
class DocumentBlobTriggerFunctionLogContextTest {

    private static final String BLOB_NAME = "c=client-1/123_20260226.json";

    private final BlobClientService blobClientService = mock(BlobClientService.class);
    private final DocumentUploadService documentUploadService = mock(DocumentUploadService.class);
    private final DocumentBlobNameResolver blobNameResolver = mock(DocumentBlobNameResolver.class);
    @SuppressWarnings("unchecked")
    private final OutputBinding<String> queueMessage = mock(OutputBinding.class);
    private final ExecutionContext context = mock(ExecutionContext.class);

    private DocumentBlobTriggerFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-blob");
        when(blobClientService.isBlobAvailable(BLOB_NAME)).thenReturn(true);
        when(blobNameResolver.getDocumentId(BLOB_NAME)).thenReturn("123");
        when(blobNameResolver.getClientId(BLOB_NAME)).thenReturn("client-1");
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobProperties properties = mock(BlobProperties.class);
        when(blobClientService.getBlobClient(BLOB_NAME)).thenReturn(blobClient);
        when(blobClient.getProperties()).thenReturn(properties);
        when(properties.getBlobSize()).thenReturn(500L * 1024 * 1024); // over the default 80 MiB limit
        final DocumentIngestionOutcome document = mock(DocumentIngestionOutcome.class);
        when(document.getDocumentId()).thenReturn("123");
        when(documentUploadService.getDocument("client-1", "123")).thenReturn(document);
        function = new DocumentBlobTriggerFunction(blobClientService, documentUploadService, blobNameResolver);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("doc= and client= resolved from the blob name are on the thread for the table write, and gone after run")
    void idsFromBlobNameAreOnThread() {
        final Map<String, String> seenByTableWrite = new HashMap<>();
        doAnswer(invocation -> {
            seenByTableWrite.putAll(MDC.getCopyOfContextMap());
            return null;
        }).when(documentUploadService).updateDocumentFileSizeOverLimit(anyString(), anyString(), anyLong(), anyLong());

        function.run(new byte[]{}, BLOB_NAME, queueMessage, context);

        assertEquals("123", seenByTableWrite.get(DOCUMENT_ID));
        assertEquals("client-1", seenByTableWrite.get(CLIENT_ID));
        assertEquals("inv-blob", seenByTableWrite.get(INVOCATION_ID));
        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    @Test
    @DisplayName("the scope is closed when the function exits by exception")
    void scopeClosesOnFailure() {
        when(documentUploadService.getDocument("client-1", "123")).thenThrow(new IllegalStateException("table unavailable"));

        assertThrows(IllegalStateException.class, () -> function.run(new byte[]{}, BLOB_NAME, queueMessage, context));

        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }
}
