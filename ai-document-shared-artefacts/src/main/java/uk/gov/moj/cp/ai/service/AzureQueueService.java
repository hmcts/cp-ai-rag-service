package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureQueueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureQueueService.class);

    private final QueueClient queueClient;

    public AzureQueueService(final String connectionString, final String queueName) {
        if (isNullOrEmpty(connectionString) || isNullOrEmpty(queueName)) {
            throw new IllegalArgumentException("connectionString or queueName is null or empty");
        }

        this.queueClient = new QueueClientBuilder()
                //Manged Identity
                .connectionString(connectionString)
                .queueName(queueName)
                .buildClient();
    }

    public AzureQueueService(final String queueName) {
        final String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");

        if (isNullOrEmpty(storageAccountName)) {
            throw new IllegalArgumentException("Environment variable STORAGE_ACCOUNT_NAME  is not set");
        }

        if (isNullOrEmpty(queueName)) {
            throw new IllegalArgumentException("connectionString or queueName is null or empty");
        }

        String queueUrl = String.format("https://%s.queue.core.windows.net", storageAccountName);
        this.queueClient = new QueueClientBuilder()
                .endpoint(queueUrl)
                .queueName(queueName)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    AzureQueueService(QueueClient queueClient) {
        this.queueClient = queueClient;
    }

    public void sendMessage(final String message) {
        LOGGER.info("Sending message to queue: {}", queueClient.getQueueName());
        queueClient.sendMessage(message);
    }
}
