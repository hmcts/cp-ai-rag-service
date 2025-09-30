package uk.gov.moj.cp.metadata.check.service;

import static java.time.Instant.now;
import static uk.gov.moj.cp.metadata.check.config.Config.getContainerName;
import static uk.gov.moj.cp.metadata.check.config.Config.getStorageAccountName;
import static uk.gov.moj.cp.metadata.check.util.BlobStatus.INGESTION_FAILED;
import static uk.gov.moj.cp.metadata.check.util.BlobStatus.METADATA_VALIDATED;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.model.QueueTaskResult;

import java.util.HashMap;
import java.util.Map;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
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
    private final TableClient tableClient;

    public QueueStorageService(final String storageConnectionString,
                               final String documentIngestionQueue,
                               final String documentIngestionOutcomeTable) {

        this.queueClient = new QueueClientBuilder()
                //Manged Identity
                .connectionString(storageConnectionString)
                .queueName(documentIngestionQueue)
                .buildClient();


        this.tableClient = new TableClientBuilder()
                .connectionString(storageConnectionString)
                .tableName(documentIngestionOutcomeTable)
                .buildClient();
    }

    public QueueStorageService(final QueueClient queueClient,
                               final TableClient tableClient) {
        this.queueClient = queueClient;
        this.tableClient = tableClient;
    }

    /**
     * Creates the queue message payload as a BlobMetadata record.
     */
    public QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> blobMetadata) {
        String documentId = blobMetadata.get(DOCUMENT_ID);

        Map<String, String> additionalMetadata = new HashMap<>();
        for (Map.Entry<String, String> entry : blobMetadata.entrySet()) {
            if (!DOCUMENT_ID.equals(entry.getKey())) {
                additionalMetadata.put(entry.getKey(), entry.getValue());
            }
        }

        String blobUrl = String.format(BLOB_URL, getStorageAccountName(), getContainerName(), blobName);
        String currentTimestamp = now().toString();

        return new QueueIngestionMetadata(documentId, blobName, additionalMetadata, blobUrl, currentTimestamp);
    }

    public QueueTaskResult sendToQueue(QueueIngestionMetadata message) {
        try {
            String messageText = objectMapper.writeValueAsString(message);
            queueClient.sendMessage(messageText);

            DocumentIngestionOutcome documentIngestionOutcome = new DocumentIngestionOutcome();
            documentIngestionOutcome.setDocumentId(message.documentId());
            documentIngestionOutcome.setDocumentName(message.documentName());
            documentIngestionOutcome.setStatus(METADATA_VALIDATED.name());
            documentIngestionOutcome.setReason(METADATA_VALIDATED.getReason());
            documentIngestionOutcome.setBlobUrl(message.blobUrl());
            documentIngestionOutcome.setTimestamp(now().toString());
            // store the status to storage table
            storeStatus(documentIngestionOutcome);

            return new QueueTaskResult(true, messageText, null);

        } catch (Exception e) {
            LOGGER.error("Failed to send message to queue: {}", e.getMessage(), e);
            DocumentIngestionOutcome errorStatusEntity = new DocumentIngestionOutcome();
            errorStatusEntity.setDocumentId(message.documentId());
            errorStatusEntity.setDocumentName(message.documentName());
            errorStatusEntity.setStatus(INGESTION_FAILED.name());
            errorStatusEntity.setReason(e.getMessage());
            errorStatusEntity.setBlobUrl(message.blobUrl());
            errorStatusEntity.setTimestamp(now().toString());
            storeStatus(errorStatusEntity);

            return new QueueTaskResult(false, null, e.getMessage());
        }
    }

    private void storeStatus(DocumentIngestionOutcome statusEntity) {
        try {
            tableClient.upsertEntity(statusEntity.toTableEntity());
            LOGGER.info("Stored status {} for doc {} in outcome table",
                    statusEntity.getStatus(), statusEntity.getDocumentId());
        } catch (Exception e) {
            LOGGER.error("Failed to store status for doc {}: {}",
                    statusEntity.getDocumentId(), e.getMessage(), e);
        }
    }
}

