package uk.gov.moj.cp.ai.service.table;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableService.class);
    private final TableClient tableClient;

    private static final String ERROR_MESSAGE = "Failed to %s record in table '%s' with partition key '%s' and row key '%s";

    public TableService(String tableName) {
        if (isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }

        this.tableClient = TableClientFactory.getInstance(tableName);
    }

    protected TableService(final TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public void insertIntoTable(final TableEntity tableEntity) throws DuplicateRecordException {
        try {

            tableClient.createEntity(tableEntity);

            LOGGER.info("Record INSERTED into table with partition key '{}' and row key '{}'", tableEntity.getPartitionKey(), tableEntity.getRowKey());

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS) {
                final String duplicateRecordErrorMessage = "Record already exists with partition key '" + tableEntity.getPartitionKey() + "' and row key '" + tableEntity.getRowKey() + "' in table '" + tableClient.getTableName() + "'";
                throw new DuplicateRecordException(duplicateRecordErrorMessage, tse);
            }

            throw new RuntimeException(String.format(ERROR_MESSAGE, "INSERT", tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), tse);
        } catch (Exception e) {
            throw new RuntimeException(String.format(ERROR_MESSAGE, "INSERT", tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
        }
    }

    public void upsertIntoTable(final TableEntity tableEntity) {
        try {

            tableClient.upsertEntity(tableEntity);

            LOGGER.info("Record UPSERTED into table with partition key '{}' and row key '{}'", tableEntity.getPartitionKey(), tableEntity.getRowKey());

        } catch (Exception e) {
            LOGGER.error(
                    "Unexpected error during UPSERT [table={}, partitionKey={}, rowKey={}]",
                    tableClient.getTableName(),
                    tableEntity.getPartitionKey(),
                    tableEntity.getRowKey(),
                    e
            );
            throw new RuntimeException(String.format(ERROR_MESSAGE, "UPSERT", tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
        }
    }

    public TableEntity getFirstDocumentMatching(final String partitionKey, final String rowKey) throws EntityRetrievalException {
        try {
            return tableClient.getEntity(partitionKey, rowKey);

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_NOT_FOUND || tse.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw new EntityRetrievalException("Failed to retrieve record matching partition key '" + partitionKey + "' and row key '" + rowKey + "'", tse);
        } catch (Exception e) {
            throw new EntityRetrievalException("Failed to retrieve record matching partition key '" + partitionKey + "' and row key '" + rowKey + "'", e);
        }
    }

}