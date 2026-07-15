package uk.gov.moj.cp.ai.service.table;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.exception.TableOperationException;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.rest.Response;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableService.class);
    private final TableClient tableClient;

    private static final String ERROR_MESSAGE = "Failed to %s record in table '%s' with partition key '%s' and row key '%s";
    private static final String INSERT_OPERATION = "INSERT";
    private static final String UPSERT_OPERATION = "UPSERT";
    private static final String CONDITIONAL_UPDATE_OPERATION = "CONDITIONAL UPDATE";

    // The Azure Tables SDK carries an entity's ETag in its properties map under this key
    // (TableEntity has no public setETag); updateEntityWithResponse(ifUnchanged=true) reads it as If-Match.
    private static final String ODATA_ETAG_PROPERTY = "odata.etag";

    /**
     * azure-core surfaces the {@code ETag} response header with its closing quote stripped
     * (observed against real Table Storage: {@code W/"datetime'...'} — unbalanced), while
     * {@code getEntity().getETag()} returns the full quoted form. The If-Match header requires
     * the full form, so restore the quote when it is missing.
     */
    private static String normalizeEtag(final String etag) {
        if (etag != null && etag.startsWith("W/\"") && !etag.endsWith("\"")) {
            return etag + "\"";
        }
        return etag;
    }

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

            throw new TableOperationException(String.format(ERROR_MESSAGE, INSERT_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), tse);
        } catch (Exception e) {
            throw new TableOperationException(String.format(ERROR_MESSAGE, INSERT_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
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
            throw new TableOperationException(String.format(ERROR_MESSAGE, UPSERT_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
        }
    }

    /**
     * Conditionally MERGEs the entity into the table, applying only if the row's current ETag
     * matches {@code etag} (If-Match / optimistic concurrency). MERGE preserves columns not
     * present on the entity.
     *
     * @return the row's NEW ETag after the successful write (the caller's fencing token rolls forward)
     * @throws EtagMismatchException if the row was modified since the ETag was obtained (HTTP 412)
     */
    public String updateEntityIfUnchanged(final TableEntity tableEntity, final String etag) {
        try {
            tableEntity.addProperty(ODATA_ETAG_PROPERTY, etag);

            final Response<Void> response = tableClient.updateEntityWithResponse(
                    tableEntity, TableEntityUpdateMode.MERGE, true, null, Context.NONE);

            LOGGER.info("Record conditionally UPDATED in table with partition key '{}' and row key '{}'",
                    tableEntity.getPartitionKey(), tableEntity.getRowKey());

            return normalizeEtag(response.getHeaders().getValue(HttpHeaderName.ETAG));

        } catch (final TableServiceException tse) {
            // Null-safe: the odata error body may be absent/unparseable, and misclassifying a
            // 412 here would let a fenced-out worker fall into an unfenced failure path.
            final boolean etagRejected =
                    (tse.getResponse() != null && tse.getResponse().getStatusCode() == 412)
                            || (tse.getValue() != null && tse.getValue().getErrorCode() == TableErrorCode.UPDATE_CONDITION_NOT_SATISFIED);
            if (etagRejected) {
                throw new EtagMismatchException(
                        "Conditional update rejected (etag changed) for table '" + tableClient.getTableName()
                                + "' partition key '" + tableEntity.getPartitionKey()
                                + "' row key '" + tableEntity.getRowKey() + "'", tse);
            }
            throw new TableOperationException(String.format(ERROR_MESSAGE, CONDITIONAL_UPDATE_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), tse);
        } catch (Exception e) {
            throw new TableOperationException(String.format(ERROR_MESSAGE, CONDITIONAL_UPDATE_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
        }
    }

    /**
     * Inserts a new entity (create-only, like {@link #insertIntoTable}) and returns the created
     * row's ETag so the caller can immediately perform conditional writes against it.
     */
    public String insertReturningEtag(final TableEntity tableEntity) throws DuplicateRecordException {
        try {
            final Response<Void> response = tableClient.createEntityWithResponse(tableEntity, null, Context.NONE);

            LOGGER.info("Record INSERTED into table with partition key '{}' and row key '{}'", tableEntity.getPartitionKey(), tableEntity.getRowKey());

            return normalizeEtag(response.getHeaders().getValue(HttpHeaderName.ETAG));

        } catch (final TableServiceException tse) {
            if (tse.getValue() != null && tse.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS) {
                final String duplicateRecordErrorMessage = "Record already exists with partition key '" + tableEntity.getPartitionKey() + "' and row key '" + tableEntity.getRowKey() + "' in table '" + tableClient.getTableName() + "'";
                throw new DuplicateRecordException(duplicateRecordErrorMessage, tse);
            }

            throw new TableOperationException(String.format(ERROR_MESSAGE, INSERT_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), tse);
        } catch (Exception e) {
            throw new TableOperationException(String.format(ERROR_MESSAGE, INSERT_OPERATION, tableClient.getTableName(), tableEntity.getPartitionKey(), tableEntity.getRowKey()), e);
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