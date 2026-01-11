package uk.gov.moj.cp.orchestrator.util;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableUtil {


    private static final Logger LOGGER = LoggerFactory.getLogger(TableUtil.class);

    public static void ensureTableExists(final String endpoint, final String tableName) {
        LOGGER.info("Connecting to '{}' and ensuring Table '{}' exists...", endpoint, tableName);

        try {
            TableServiceClient tableClient = new TableServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(getCredentialInstance())
                    .buildClient();

            tableClient.createTableIfNotExists(tableName);

            LOGGER.info("Table '{}' created successfully (or already existed).", tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create table.", e);
        }
    }

    public static void deleteTable(final String endpoint, final String tableName) {
        LOGGER.info("Connecting to '{}' and deleting Table '{}'...", endpoint, tableName);

        try {
            TableServiceClient tableClient = new TableServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(getCredentialInstance())
                    .buildClient();

            tableClient.deleteTable(tableName);

            LOGGER.info("Table '{}' deleted successfully", tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete table.", e);
        }
    }
}
