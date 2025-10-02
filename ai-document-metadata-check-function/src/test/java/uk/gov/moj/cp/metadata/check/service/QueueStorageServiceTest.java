package uk.gov.moj.cp.metadata.check.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;

import java.util.HashMap;
import java.util.Map;

import com.azure.data.tables.TableClient;
import com.azure.storage.queue.QueueClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueueStorageServiceTest {

    @Mock
    private QueueClient queueClient;

    @Mock
    private TableClient tableClient;

    private QueueStorageService queueStorageService;

    @BeforeEach
    void setUp() {
        // Set up required environment variables for testing
        System.setProperty("STORAGE_ACCOUNT_NAME", "teststorage");
        System.setProperty("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME", "testcontainer");
        queueStorageService = new QueueStorageService(queueClient);
    }

    @Test
    @DisplayName("Send to Queue Successfully")
    void shouldSendToQueueSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("content_type", "application/pdf");

        when(queueClient.sendMessage(anyString())).thenReturn(null);

        // when
        queueStorageService.sendToQueue(documentName, metadata);

        // then
        verify(queueClient).sendMessage(anyString());
    }

    @Test
    @DisplayName("Handle Queue Send Failure")
    void shouldHandleQueueSendFailure() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("content_type", "application/pdf");

        when(queueClient.sendMessage(anyString())).thenThrow(new RuntimeException("Queue service unavailable"));

        // when & then
        assertThrows(uk.gov.moj.cp.metadata.check.exception.QueueSendException.class, () -> {
            queueStorageService.sendToQueue(documentName, metadata);
        });

        verify(queueClient).sendMessage(anyString());
    }

    @Test
    @DisplayName("Handle Queue Send Failure with Exception")
    void shouldHandleQueueSendFailureWithException() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("content_type", "application/pdf");

        when(queueClient.sendMessage(anyString())).thenThrow(new RuntimeException("Queue service unavailable"));

        // when & then
        assertThrows(uk.gov.moj.cp.metadata.check.exception.QueueSendException.class, () -> {
            queueStorageService.sendToQueue(documentName, metadata);
        });

        verify(queueClient).sendMessage(anyString());
    }
}
