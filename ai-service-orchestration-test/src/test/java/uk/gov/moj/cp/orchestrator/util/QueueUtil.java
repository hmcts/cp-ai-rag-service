package uk.gov.moj.cp.orchestrator.util;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueUtil {


    private static final Logger LOGGER = LoggerFactory.getLogger(QueueUtil.class);

    public static void ensureQueueExists(final String endpoint, final String queueName) {
        LOGGER.info("Connecting to '{}' and ensuring queue '{}' exists...", endpoint, queueName);

        try {
            final QueueClient queueClient = getQueueClient(endpoint, queueName);

            queueClient.createIfNotExists();

            LOGGER.info("Container '{}' created successfully (or already existed).", queueName);
        } catch (Exception e) {
            // Handle or rethrow if the queue could not be created
            throw new RuntimeException("Failed to create queue", e);
        }
    }


    public static void deleteQueue(final String endpoint, final String queueName) {
        LOGGER.info("Connecting to '{}' and ensuring container '{}' exists before deleting it", endpoint, queueName);

        try {
            final QueueClient queueClient = getQueueClient(endpoint, queueName);

            final boolean queueDeleted = queueClient.deleteIfExists();

            LOGGER.info("Queue '{}' deletion status {}", queueName, queueDeleted ? "deleted" : "not deleted");
        } catch (Exception e) {
            // Handle or rethrow if the queue could not be created
            throw new RuntimeException("Failed to delete queue", e);
        }
    }

    private static @NotNull QueueClient getQueueClient(final String endpoint, final String queueName) {
        return new QueueClientBuilder()
                .endpoint(endpoint)
                .credential(getCredentialInstance())
                .queueName(queueName)
                .buildClient();
    }
}
