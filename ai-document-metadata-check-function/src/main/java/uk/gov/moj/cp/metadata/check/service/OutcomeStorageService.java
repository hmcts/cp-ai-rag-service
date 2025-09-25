package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.metadata.check.config.Config.getStorageConnectionString;
import static uk.gov.moj.cp.metadata.check.config.Config.getTableName;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;

public class OutcomeStorageService {
    private final TableClient tableClient;
    private static final String STORAGE_CONNECTION_STRING = getStorageConnectionString();
    private static final String DOCUMENT_INGESTION_OUTCOME_TABLE = getTableName();

    public OutcomeStorageService() {
        this.tableClient = new TableClientBuilder()
                .connectionString(STORAGE_CONNECTION_STRING)
                .tableName(DOCUMENT_INGESTION_OUTCOME_TABLE)
                .buildClient();
    }

    public void store(DocumentIngestionOutcome statusEntity) {
        tableClient.upsertEntity(statusEntity.toTableEntity());
    }
}