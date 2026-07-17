package uk.gov.moj.cp.orchestrator.util;

import static org.awaitility.Awaitility.await;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import java.time.Duration;

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

    /**
     * Enqueues a message directly (Base64-encoded, matching what the Functions host produces
     * and expects for Java queue triggers). Lets tests simulate a duplicate delivery by
     * re-sending a payload the worker has already processed.
     */
    public static void sendMessage(final String endpoint, final String queueName, final String payload) {
        LOGGER.info("Sending message to queue '{}' on '{}'", queueName, endpoint);

        try {
            final QueueClient queueClient = getQueueClient(endpoint, queueName);

            queueClient.sendMessage(java.util.Base64.getEncoder()
                    .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        } catch (Exception e) {
            throw new RuntimeException("Failed to send message to queue", e);
        }
    }

    /**
     * Blocks until the queue reports no messages (visible or in-flight), proving a delivered
     * message has been consumed and deleted — a deterministic alternative to sleeping.
     */
    public static void awaitQueueDrained(final String endpoint, final String queueName, final Duration timeout) {
        final QueueClient queueClient = getQueueClient(endpoint, queueName);
        await()
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    final int count = queueClient.getProperties().getApproximateMessagesCount();
                    LOGGER.debug("Queue '{}' approximate message count: {}", queueName, count);
                    return count == 0;
                });
    }

    private static @NotNull QueueClient getQueueClient(final String endpoint, final String queueName) {
        return new QueueClientBuilder()
                .endpoint(endpoint)
                .credential(getCredentialInstance())
                .queueName(queueName)
                .buildClient();
    }
}
