package uk.gov.moj.cp.ai.service;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TableStorageService.class);
    private final TableClient tableClient;


    public TableStorageService(final String storageConnectionString,
                               final String tableName) {
        this.tableClient = new TableClientBuilder()
                .connectionString(storageConnectionString)
                .tableName(tableName)
                .buildClient();
    }

    public void recordOutcome(DocumentIngestionOutcome outcome) {
        try {
            tableClient.upsertEntity(outcome.toTableEntity());
            LOGGER.info("Recorded outcome: status={} docId={} docName={}",
                    outcome.getStatus(), outcome.getDocumentId(), outcome.getDocumentName());
        } catch (Exception e) {
            LOGGER.error("Failed to record outcome for docId={} docName={}: {}",
                    outcome.getDocumentId(), outcome.getDocumentName(), e.getMessage(), e);
        }
    }
}
