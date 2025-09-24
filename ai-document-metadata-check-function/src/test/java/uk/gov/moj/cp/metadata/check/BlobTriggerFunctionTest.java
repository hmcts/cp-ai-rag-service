package uk.gov.moj.cp.metadata.check;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.metadata.check.service.BlobMetadataValidationService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import java.util.HashMap;
import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobTriggerFunctionTest {

    private BlobTriggerFunction blobTriggerFunction;
    private BlobMetadataValidationService blobMetadataValidationServiceMock;
    private QueueStorageService queueStorageServiceMock;
    private ExecutionContext contextMock;

    @BeforeEach
    void setUp() {
        blobMetadataValidationServiceMock = mock(BlobMetadataValidationService.class);
        queueStorageServiceMock = mock(QueueStorageService.class);
        contextMock = mock(ExecutionContext.class);
        blobTriggerFunction = new BlobTriggerFunction(blobMetadataValidationServiceMock, queueStorageServiceMock);
    }

    @Test
    @DisplayName("Processes Document with valid Metadata")
    void shouldProcessValidBlobWithMetadata() throws Exception {
        // given
        String documentName = "test-document.pdf";
        String invocationId = "test-invocation-123";

        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        blobMetadata.put("case_id", "CASE-12345");

        //when
        when(contextMock.getInvocationId()).thenReturn(invocationId);
        when(blobMetadataValidationServiceMock.extractBlobMetadata(documentName)).thenReturn(blobMetadata);
        doNothing().when(queueStorageServiceMock).sendToQueue(any(Map.class));

        // then
        blobTriggerFunction.run(documentName, documentName, contextMock);

        // Assert
        verify(blobMetadataValidationServiceMock).extractBlobMetadata(documentName);
        verify(queueStorageServiceMock).sendToQueue(any(Map.class));
    }

    @Test
    @DisplayName("Handles document with invalid metadata and does not send to queue")
    void shouldHandleInvalidMetadata() throws Exception {
        // Given
        String documentName = "test-document.pdf";
        String invocationId = "test-invocation-456";

        Map<String, String> invalidMetadata = new HashMap<>();
        // Missing required document_id
        invalidMetadata.put("case_id", "CASE-12345");


        when(contextMock.getInvocationId()).thenReturn(invocationId);
        when(blobMetadataValidationServiceMock.extractBlobMetadata(documentName)).thenReturn(invalidMetadata);

        // then
        blobTriggerFunction.run(documentName, documentName, contextMock);

        // Assert
        verify(blobMetadataValidationServiceMock).extractBlobMetadata(documentName);
    }
}
