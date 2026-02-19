package uk.gov.moj.cp.ai.service.table;

import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TIMESTAMP;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateKeyForRowAndPartition;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import java.util.Objects;

import com.azure.data.tables.models.TableEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentIngestionOutcomeTableService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionOutcomeTableService.class);

    private final TableService tableService;

    private static final String ERROR_MESSAGE = "Failed to %s record for document '%s' with ID: '%s";

    public DocumentIngestionOutcomeTableService(String tableName) {
        if (isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }

        this.tableService = new TableService(tableName);

    }

    protected DocumentIngestionOutcomeTableService(final TableService tableService) {
        this.tableService = tableService;
    }

    public void insertIntoTable(String documentName, String documentId, String status, String reason) throws DuplicateRecordException {

        final TableEntity entity = getTableEntity(documentName, documentId, status, reason);

        tableService.insertIntoTable(entity);

        LOGGER.info("Record INSERTED into table with status '{}' for document '{}' with ID '{}'", status, documentName, documentId);

    }

    public void insertUploadInitiated(String documentName, String documentId, String status, String reason) throws DuplicateRecordException {
        final TableEntity entity = new TableEntity(documentId, documentId);
        entity.addProperty(TC_DOCUMENT_FILE_NAME, documentName);
        entity.addProperty(TC_DOCUMENT_ID, documentId);
        entity.addProperty(TC_DOCUMENT_STATUS, status);
        entity.addProperty(TC_REASON, reason);

        tableService.insertIntoTable(entity);

        LOGGER.info("Document upload record INSERTED into table with status '{}' for document '{}' with ID '{}'", status, documentName, documentId);
    }

    public void upsertIntoTable(String documentName, String documentId, String status, String reason) {
        try {

            final TableEntity entity = getTableEntity(documentName, documentId, status, reason);

            tableService.upsertIntoTable(entity);

            LOGGER.info("Record UPSERTED into table with status={} for document '{}' with ID '{}'", status, documentName, documentId);

        } catch (Exception e) {
            throw new RuntimeException(String.format(ERROR_MESSAGE, "UPSERT", documentName, documentId), e);
        }
    }

    public DocumentIngestionOutcome getFirstDocumentMatching(String documentName) throws EntityRetrievalException {
        String rowAndPartitionKey = generateKeyForRowAndPartition(documentName);

        final TableEntity entity = tableService.getFirstDocumentMatching(rowAndPartitionKey, rowAndPartitionKey);
        if (null == entity) {
            return null;
        }
        return new DocumentIngestionOutcome(
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_ID)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_FILE_NAME)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_STATUS)),
                getPropertyAsString(entity.getProperty(TC_REASON)),
                getPropertyAsString(entity.getProperty(TC_TIMESTAMP)));

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