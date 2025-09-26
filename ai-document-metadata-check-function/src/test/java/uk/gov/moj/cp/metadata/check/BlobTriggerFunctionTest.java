package uk.gov.moj.cp.metadata.check;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.BlobMetadata;
import uk.gov.moj.cp.metadata.check.service.BlobMetadataService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import java.util.HashMap;
import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobTriggerFunctionTest {

    private BlobTriggerFunction blobTriggerFunction;
    private BlobMetadataService blobMetadataServiceMock;
    private QueueStorageService queueStorageServiceMock;
    private ExecutionContext contextMock;

    @BeforeEach
    void setUp() {
        // Set required system properties for testing
        System.setProperty("STORAGE_ACCOUNT_NAME", "teststorageaccount");
        System.setProperty("DOCUMENT_CONTAINER_NAME", "testcontainer");

        blobMetadataServiceMock = mock(BlobMetadataService.class);
        queueStorageServiceMock = mock(QueueStorageService.class);
        contextMock = mock(ExecutionContext.class);
        blobTriggerFunction = new BlobTriggerFunction(blobMetadataServiceMock, queueStorageServiceMock);
    }

    @Test
    @DisplayName("Processes Document with valid Metadata")
    void shouldProcessValidBlobWithMetadata() {
        // given
        String documentName = "test-document.pdf";
        String invocationId = "test-invocation-123";

        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        blobMetadata.put("case_id", "CASE-12345");

        //when
        when(contextMock.getInvocationId()).thenReturn(invocationId);
        when(blobMetadataServiceMock.extractBlobMetadata(documentName)).thenReturn(blobMetadata);
        doNothing().when(queueStorageServiceMock).sendToQueue(any(BlobMetadata.class));

        // then
        blobTriggerFunction.run(documentName, documentName, contextMock);

        // Assert
        verify(blobMetadataServiceMock).extractBlobMetadata(documentName);
        verify(queueStorageServiceMock).sendToQueue(any(BlobMetadata.class));
    }

    @Test
    @DisplayName("Handles document with invalid metadata and does not send to queue")
    void shouldHandleInvalidMetadata() {
        // Given
        String documentName = "test-document.pdf";
        String invocationId = "test-invocation-456";

        Map<String, String> invalidMetadata = new HashMap<>();
        // Missing required document_id
        invalidMetadata.put("case_id", "CASE-12345");


        when(contextMock.getInvocationId()).thenReturn(invocationId);
        when(blobMetadataServiceMock.extractBlobMetadata(documentName)).thenReturn(invalidMetadata);

        // then
        blobTriggerFunction.run(documentName, documentName, contextMock);

        // Assert
        verify(blobMetadataServiceMock).extractBlobMetadata(documentName);
    }
}
