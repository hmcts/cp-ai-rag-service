package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;

import java.time.Instant;

public class TableStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableStorageService.class);
    private final TableClient tableClient;

    public TableStorageService(String connectionString, String tableName) {

        if (isNullOrEmpty(connectionString) || isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table connection string and name cannot be null or empty");
        }
        this.tableClient = new TableClientBuilder()
                .connectionString(connectionString)
                .tableName(tableName)
                .buildClient();
    }

    public void upsertDocumentOutcome(String documentName, String documentId, String status, String reason) {
        try {

            DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
            outcome.generateDefaultPartitionKey(documentId);
            outcome.generateRowKeyFrom(documentName);
            outcome.setDocumentName(documentName);
            outcome.setDocumentId(documentId);
            outcome.setStatus(status);
            outcome.setReason(reason);
            outcome.setTimestamp(Instant.now().toString());


            TableEntity entity = new TableEntity(outcome.getPartitionKey(), outcome.getRowKey());
            entity.addProperty("documentName", outcome.getDocumentName());
            entity.addProperty("documentId", outcome.getDocumentId());
            entity.addProperty("status", outcome.getStatus());
            entity.addProperty("reason", outcome.getReason());
            entity.addProperty("timestamp", outcome.getTimestamp());


            tableClient.upsertEntity(entity);

            LOGGER.info("event=outcome_upserted status={} documentName={} documentId={}",
                    status, documentName, documentId);

        } catch (Exception e) {
            LOGGER.error("Failed to upsert document outcome for document: {} (ID: {}) ",
                    documentName, documentId, e);
            throw new RuntimeException("Failed to upsert document outcome", e);
        }
    }

}