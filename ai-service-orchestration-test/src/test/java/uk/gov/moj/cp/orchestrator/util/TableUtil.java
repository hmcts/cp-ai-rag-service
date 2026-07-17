package uk.gov.moj.cp.orchestrator.util;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableServiceException;
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

    /**
     * Reads a single property from a table entity, or returns {@code null} when the entity or
     * property does not exist (yet) — callers poll on the null/non-null transition.
     */
    public static Object getEntityProperty(final String endpoint, final String tableName,
                                           final String partitionKey, final String rowKey, final String propertyName) {
        try {
            final TableClient tableClient = new TableServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(getCredentialInstance())
                    .buildClient()
                    .getTableClient(tableName);
            return tableClient.getEntity(partitionKey, rowKey).getProperty(propertyName);
        } catch (TableServiceException e) {
            return null;
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
