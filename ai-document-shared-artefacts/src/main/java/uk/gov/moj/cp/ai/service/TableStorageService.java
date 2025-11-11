package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TIMESTAMP;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateRowKey;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableStorageService.class);
    private final TableClient tableClient;

    public TableStorageService(final TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public TableStorageService(String endpoint, String tableName) {

        if (isNullOrEmpty(endpoint) || isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table storage account endpoint and table name cannot be null or empty");
        }

        LOGGER.info("Connecting to endpoint {} and table {}", endpoint, tableName);

        this.tableClient = new TableClientBuilder()
                .endpoint(endpoint)
                .tableName(tableName)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        LOGGER.info("Initialized Table Storage client with managed identity.");
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


            TableEntity entity = new TableEntity(outcome.getPartitionKey(), outcome.getRowKey());
            entity.addProperty(TC_DOCUMENT_FILE_NAME, outcome.getDocumentName());
            entity.addProperty(TC_DOCUMENT_ID, outcome.getDocumentId());
            entity.addProperty(TC_DOCUMENT_STATUS, outcome.getStatus());
            entity.addProperty(TC_REASON, outcome.getReason());

            tableClient.upsertEntity(entity);

            LOGGER.info("event=outcome_upserted status={} documentName={} documentId={}",
                    status, documentName, documentId);

        } catch (Exception e) {
            LOGGER.error("Failed to upsert document outcome for document: {} (ID: {}) ",
                    documentName, documentId, e);
            throw new RuntimeException("Failed to upsert document outcome", e);
        }
    }

    public DocumentIngestionOutcome getFirstDocumentMatching(String documentName) {
        String targetRowKey = generateRowKey(documentName);

        String filter = String.format("RowKey eq '%s'", targetRowKey);

        // 3. Execute the query
        final ListEntitiesOptions options = new ListEntitiesOptions().setFilter(filter).setTop(1);
        Stream<TableEntity> entities = tableClient.listEntities(options, null, null).stream();

        Optional<TableEntity> foundEntity = entities.findFirst();

        return foundEntity.map(fe -> new DocumentIngestionOutcome(
                getPropertyAsString(fe.getProperty(TC_DOCUMENT_ID)),
                getPropertyAsString(fe.getProperty(TC_DOCUMENT_FILE_NAME)),
                getPropertyAsString(fe.getProperty(TC_DOCUMENT_STATUS)),
                getPropertyAsString(fe.getProperty(TC_REASON)),
                getPropertyAsString(fe.getProperty(TC_TIMESTAMP)))
        ).orElse(null);
    }

    private static String getPropertyAsString(final Object value) {
        if (Objects.nonNull(value)) {
            return value.toString();
        }
        return null;
    }

}