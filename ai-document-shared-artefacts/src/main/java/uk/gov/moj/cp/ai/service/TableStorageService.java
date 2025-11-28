package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TIMESTAMP;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateKeyForRowAndPartition;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import java.util.Objects;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TableStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableStorageService.class);
    private final TableClient tableClient;

    private static final String ERROR_MESSAGE = "Failed to %s record for document '%s' with ID: '%s";

    public TableStorageService(String endpoint, String tableName) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table storage endpoint and table name cannot be null or empty.");
        }

        this.tableClient = TableClientFactory.getInstance(endpoint, tableName);
    }

    protected TableStorageService(final TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public void insertIntoTable(String documentName, String documentId, String status, String reason) throws DuplicateRecordException {
        try {

            final TableEntity entity = getTableEntity(documentName, documentId, status, reason);

            tableClient.createEntity(entity);

            LOGGER.info("Record INSERTED into table with status '{}' for document '{}' with ID '{}'", status, documentName, documentId);

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS) {
                final String duplicateRecordErrorMessage = "Document outcome record already exists for document '" + documentName + "' with ID '" + documentId + "'";
                throw new DuplicateRecordException(duplicateRecordErrorMessage, tse);
            }

            throw new RuntimeException(String.format(ERROR_MESSAGE, "INSERT", documentName, documentId), tse);
        } catch (Exception e) {
            throw new RuntimeException(String.format(ERROR_MESSAGE, "INSERT", documentName, documentId), e);
        }
    }

    public void upsertIntoTable(String documentName, String documentId, String status, String reason) {
        try {

            final TableEntity entity = getTableEntity(documentName, documentId, status, reason);

            tableClient.upsertEntity(entity);

            LOGGER.info("Record UPSERTED into table with status={} for document '{}' with ID '{}'", status, documentName, documentId);

        } catch (Exception e) {
            throw new RuntimeException(String.format(ERROR_MESSAGE, "UPSERT", documentName, documentId), e);
        }
    }

    public DocumentIngestionOutcome getFirstDocumentMatching(String documentName) throws EntityRetrievalException {
        String rowAndPartitionKey = generateKeyForRowAndPartition(documentName);

        try {
            final TableEntity entity = tableClient.getEntity(rowAndPartitionKey, rowAndPartitionKey);
            return new DocumentIngestionOutcome(
                    getPropertyAsString(entity.getProperty(TC_DOCUMENT_ID)),
                    getPropertyAsString(entity.getProperty(TC_DOCUMENT_FILE_NAME)),
                    getPropertyAsString(entity.getProperty(TC_DOCUMENT_STATUS)),
                    getPropertyAsString(entity.getProperty(TC_REASON)),
                    getPropertyAsString(entity.getProperty(TC_TIMESTAMP)));
        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_NOT_FOUND || tse.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw new EntityRetrievalException("Failed to retrieve record for document '" + documentName + "'", tse);
        } catch (Exception e) {
            throw new EntityRetrievalException("Failed to retrieve record for document '" + documentName + "'", e);
        }
    }

    private String getPropertyAsString(final Object value) {
        if (Objects.nonNull(value)) {
            return value.toString();
        }
        return null;
    }

    private TableEntity getTableEntity(final String documentName, final String documentId, final String status, final String reason) {
        final String rowAndPartitionKey = generateKeyForRowAndPartition(documentName);

        TableEntity entity = new TableEntity(rowAndPartitionKey, rowAndPartitionKey);
        entity.addProperty(TC_DOCUMENT_FILE_NAME, documentName);
        entity.addProperty(TC_DOCUMENT_ID, documentId);
        entity.addProperty(TC_DOCUMENT_STATUS, status);
        entity.addProperty(TC_REASON, reason);
        return entity;
    }

}