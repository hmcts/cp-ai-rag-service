package uk.gov.moj.cp.metadata.check.service;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;

public class OutcomeStorageService {
    private final TableClient tableClient;


    public OutcomeStorageService(final String storageConnectionString,
                                 final String documentIngestionTableName) {
        this.tableClient = new TableClientBuilder()
                .connectionString(storageConnectionString)
                .tableName(documentIngestionTableName)
                .buildClient();
    }

    OutcomeStorageService(final TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public void store(DocumentIngestionOutcome statusEntity) {
        tableClient.upsertEntity(statusEntity.toTableEntity());
    }
}