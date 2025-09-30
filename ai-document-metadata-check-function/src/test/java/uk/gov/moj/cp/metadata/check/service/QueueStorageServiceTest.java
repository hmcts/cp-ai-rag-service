package uk.gov.moj.cp.metadata.check.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.model.QueueTaskResult;

import com.azure.data.tables.TableClient;
import com.azure.storage.queue.QueueClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueStorageServiceTest {

    @Mock
    private QueueClient queueClient;

    @Mock
    private TableClient tableClient;

    private QueueStorageService queueStorageService;

    @BeforeEach
    void setUp() {
        queueStorageService = new QueueStorageService(queueClient, tableClient);
    }

    @Test
    @DisplayName("Send to Queue Successfully")
    void shouldSendToQueueSuccessfully() {
        // given
        QueueIngestionMetadata message = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            "test.pdf",
            new HashMap<>(),
            "https://test.blob.core.windows.net/container/test.pdf",
            "2023-01-01T00:00:00Z"
        );

        when(queueClient.sendMessage(anyString())).thenReturn(null);

        // when
        QueueTaskResult result = queueStorageService.sendToQueue(message);

        // then
        assertNotNull(result);
        assertTrue(result.success());
        assertNotNull(result.messageId());
        assertNull(result.errorMessage());
        verify(queueClient).sendMessage(anyString());
        verify(tableClient).upsertEntity(any());
    }

    @Test
    @DisplayName("Handle Queue Send Failure")
    void shouldHandleQueueSendFailure() {
        // given
        QueueIngestionMetadata message = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            "test.pdf",
            new HashMap<>(),
            "https://test.blob.core.windows.net/container/test.pdf",
            "2023-01-01T00:00:00Z"
        );

        when(queueClient.sendMessage(anyString())).thenThrow(new RuntimeException("Queue service unavailable"));

        // when
        QueueTaskResult result = queueStorageService.sendToQueue(message);

        // then
        assertNotNull(result);
        assertFalse(result.success());
        assertNull(result.messageId());
        assertEquals("Queue service unavailable", result.errorMessage());
        verify(queueClient).sendMessage(anyString());
        verify(tableClient).upsertEntity(any());
    }

    @Test
    @DisplayName("Handle Table Storage Failure During Success")
    void shouldHandleTableStorageFailureDuringSuccess() {
        // given
        QueueIngestionMetadata message = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            "test.pdf",
            new HashMap<>(),
            "https://test.blob.core.windows.net/container/test.pdf",
            "2023-01-01T00:00:00Z"
        );

        when(queueClient.sendMessage(anyString())).thenReturn(null);
        doThrow(new RuntimeException("Table storage error")).when(tableClient).upsertEntity(any());

        // when
        QueueTaskResult result = queueStorageService.sendToQueue(message);

        // then
        assertNotNull(result);
        assertTrue(result.success()); // Queue send was successful
        assertNotNull(result.messageId());
        assertNull(result.errorMessage());
        verify(queueClient).sendMessage(anyString());
        verify(tableClient).upsertEntity(any());
    }

    @Test
    @DisplayName("Handle Table Storage Failure During Error")
    void shouldHandleTableStorageFailureDuringError() {
        // given
        QueueIngestionMetadata message = new QueueIngestionMetadata(
            "123e4567-e89b-12d3-a456-426614174000",
            "test.pdf",
            new HashMap<>(),
            "https://test.blob.core.windows.net/container/test.pdf",
            "2023-01-01T00:00:00Z"
        );

        when(queueClient.sendMessage(anyString())).thenThrow(new RuntimeException("Queue service unavailable"));
        doThrow(new RuntimeException("Table storage error")).when(tableClient).upsertEntity(any());

        // when
        QueueTaskResult result = queueStorageService.sendToQueue(message);

        // then
        assertNotNull(result);
        assertFalse(result.success());
        assertNull(result.messageId());
        assertEquals("Queue service unavailable", result.errorMessage());
        verify(queueClient).sendMessage(anyString());
        verify(tableClient).upsertEntity(any());
    }
}
