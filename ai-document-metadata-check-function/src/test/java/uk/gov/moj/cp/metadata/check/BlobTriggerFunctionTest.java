package uk.gov.moj.cp.metadata.check;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.model.QueueTaskResult;
import uk.gov.moj.cp.metadata.check.service.BlobMetadataService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import com.microsoft.azure.functions.ExecutionContext;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlobTriggerFunctionTest {

    @Mock
    private BlobMetadataService blobMetadataService;

    @Mock
    private QueueStorageService queueStorageService;

    @Mock
    private ExecutionContext executionContext;

    private BlobTriggerFunction blobTriggerFunction;

    @BeforeEach
    void setUp() {
        blobTriggerFunction = new BlobTriggerFunction(blobMetadataService, queueStorageService);
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessBlobSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        
        QueueIngestionMetadata queueMessage = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000", 
            documentName, 
            new HashMap<>(), 
            "https://test.blob.core.windows.net/container/test.pdf",
            "2023-01-01T00:00:00Z"
        );
        
        QueueTaskResult queueTaskResult = new QueueTaskResult(true, "message", null);

        when(blobMetadataService.processBlobMetadata(documentName)).thenReturn(metadata);
        when(queueStorageService.createQueueMessage(documentName, metadata)).thenReturn(queueMessage);
        when(queueStorageService.sendToQueue(queueMessage)).thenReturn(queueTaskResult);
        when(executionContext.getInvocationId()).thenReturn("test-invocation-id");

        // When
        blobTriggerFunction.run(documentName, executionContext);

        // Then
        verify(blobMetadataService).processBlobMetadata(documentName);
        verify(queueStorageService).createQueueMessage(documentName, metadata);
        verify(queueStorageService).sendToQueue(queueMessage);
    }

    @Test
    void shouldHandleQueueFailure() {
        // given
        String documentName = "test-document.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        
        QueueIngestionMetadata queueMessage = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000", 
            documentName, 
            new HashMap<>(), 
            "https://test.blob.core.windows.net/container/test-document.pdf", 
            "2023-01-01T00:00:00Z"
        );
        
        QueueTaskResult failureResult = new QueueTaskResult(false, null, "Queue service unavailable");

        when(blobMetadataService.processBlobMetadata(documentName)).thenReturn(metadata);
        when(queueStorageService.createQueueMessage(documentName, metadata)).thenReturn(queueMessage);
        when(queueStorageService.sendToQueue(queueMessage)).thenReturn(failureResult);
        when(executionContext.getInvocationId()).thenReturn("test-invocation-id");

        // when
        blobTriggerFunction.run(documentName, executionContext);

        // then
        verify(blobMetadataService).processBlobMetadata(documentName);
        verify(queueStorageService).createQueueMessage(documentName, metadata);
        verify(queueStorageService).sendToQueue(queueMessage);
    }
}
