package uk.gov.moj.cp.metadata.check.service;

import static java.time.Instant.now;
import static uk.gov.moj.cp.metadata.check.config.Config.getContainerName;
import static uk.gov.moj.cp.metadata.check.config.Config.getStorageAccountName;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.metadata.check.exception.QueueSendException;

import java.util.Map;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueStorageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BLOB_URL = "https://%s.blob.core.windows.net/%s/%s";
    private static final String DOCUMENT_ID = "document_id";
    private final QueueClient queueClient;


    public QueueStorageService(final String storageConnectionString,
                               final String documentIngestionQueue) {

        this.queueClient = new QueueClientBuilder()
                //Manged Identity
                .connectionString(storageConnectionString)
                .queueName(documentIngestionQueue)
                .buildClient();

    }

    public QueueStorageService(final QueueClient queueClient) {
        this.queueClient = queueClient;
    }


    public void sendToQueue(String blobName, Map<String, String> metadata) {
        try {
            String documentId = metadata.get(DOCUMENT_ID);
            String blobUrl = String.format(BLOB_URL, getStorageAccountName(), getContainerName(), blobName);
            String currentTimestamp = now().toString();

            QueueIngestionMetadata queueMessage = new QueueIngestionMetadata(
                    documentId,
                    blobName,
                    metadata,
                    blobUrl,
                    currentTimestamp
            );

            String messageText = objectMapper.writeValueAsString(queueMessage);
            queueClient.sendMessage(messageText);

            LOGGER.info("Message sent to queue for document: {}", blobName);

        } catch (Exception e) {
            LOGGER.error("Failed to send message to queue for document: {}", blobName, e);
            throw new QueueSendException("Failed to send message to queue for blob: " + blobName);
        }
    }

}
