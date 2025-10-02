package uk.gov.moj.cp.ai.service;

import com.azure.storage.queue.QueueClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AzureQueueServiceTest {

    @Mock
    private QueueClient mockQueueClient;

    private AzureQueueService azureQueueService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        azureQueueService = new AzureQueueService(mockQueueClient);
    }

    @Test
    void constructorThrowsExceptionWhenConnectionStringOrQueueNameIsNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService(null, "queueName"));
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService("", "queueName"));
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService("connectionString", null));
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService("connectionString", ""));
    }

    @Test
    void constructorThrowsExceptionWhenStorageAccountNameIsNotSet() {
        System.clearProperty("STORAGE_ACCOUNT_NAME");
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService("queueName"));
    }

    @Test
    void constructorThrowsExceptionWhenQueueNameIsNullOrEmptyWithManagedIdentity() {
        System.setProperty("STORAGE_ACCOUNT_NAME", "testAccount");
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService((String) null));
        assertThrows(IllegalArgumentException.class, () -> new AzureQueueService(""));
    }

    @Test
    void sendMessageLogsAndSendsMessageToQueue() {
        String message = "Test message";
        when(mockQueueClient.getQueueName()).thenReturn("testQueue");

        azureQueueService.sendMessage(message);

        verify(mockQueueClient).sendMessage(message);
    }
}
