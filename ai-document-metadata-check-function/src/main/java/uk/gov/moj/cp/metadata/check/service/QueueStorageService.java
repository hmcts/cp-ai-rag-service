package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.metadata.check.config.Config.getQueueName;
import static uk.gov.moj.cp.metadata.check.config.Config.getStorageConnectionString;
import uk.gov.moj.cp.ai.model.BlobMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

public class QueueStorageService {

    private static final Logger logger = LoggerFactory.getLogger(QueueStorageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Sends message to Azure Storage Queue.
     */
    public void sendToQueue(BlobMetadata message) {
        try {

            String connectionString = getStorageConnectionString();
            String queueName = getQueueName();

            QueueClient queueClient = new QueueClientBuilder()
                    // TODO change to MangedIdentity
                    .connectionString(connectionString)
                    .queueName(queueName)
                    .buildClient();

            String messageText = objectMapper.writeValueAsString(message);

            // Send message to queue
            queueClient.sendMessage(messageText);

            logger.info("Message sent to queue {}: {}", queueName, messageText);

        } catch (Exception e) {
            logger.error("Failed to send message to queue: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send message to queue", e);
        }
    }
}
