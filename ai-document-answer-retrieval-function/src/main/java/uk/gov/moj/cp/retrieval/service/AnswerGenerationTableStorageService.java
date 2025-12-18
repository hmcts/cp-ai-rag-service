package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateKeyForRowAndPartition;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Table storage service for Answer Generation outcomes.
 */
public class AnswerGenerationTableStorageService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnswerGenerationTableStorageService.class);

    private static final String ERROR_MESSAGE =
            "Failed to %s answer generation record for transactionId '%s'";

    private final TableClient tableClient;

    public AnswerGenerationTableStorageService(final String tableName) {
        if (isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }
        this.tableClient = TableClientFactory.getInstance(tableName);
    }

    protected AnswerGenerationTableStorageService(final TableClient tableClient) {
        this.tableClient = tableClient;
    }

    // ---------------------------------------------------------------------
    // INSERT
    // ---------------------------------------------------------------------

    public void insertIntoTable(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final String chunkedEntries,
            final String llmResponse,
            final AnswerGenerationStatus status,
            final String reason,
            final Long responseGenerationDuration
    ) throws DuplicateRecordException {

        try {
            final TableEntity entity = buildEntity(
                    transactionId,
                    userQuery,
                    queryPrompt,
                    chunkedEntries,
                    llmResponse,
                    status,
                    reason,
                    responseGenerationDuration
            );

            tableClient.createEntity(entity);

            LOGGER.info(
                    "Answer generation record INSERTED with status={} for transactionId={}",
                    status, transactionId);

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_ALREADY_EXISTS) {
                throw new DuplicateRecordException(
                        "Answer generation record already exists for transactionId " + transactionId,
                        tse);
            }
            throw new RuntimeException(
                    String.format(ERROR_MESSAGE, "INSERT", transactionId), tse);
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(ERROR_MESSAGE, "INSERT", transactionId), e);
        }
    }

    // ---------------------------------------------------------------------
    // UPSERT
    // ---------------------------------------------------------------------

    public void upsertIntoTable(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final String chunkedEntries,
            final String llmResponse,
            final AnswerGenerationStatus status,
            final String reason,
            final Long responseGenerationDuration
    ) {

        try {
            final TableEntity entity = buildEntity(
                    transactionId,
                    userQuery,
                    queryPrompt,
                    chunkedEntries,
                    llmResponse,
                    status,
                    reason,
                    responseGenerationDuration
            );

            tableClient.upsertEntity(entity);

            LOGGER.info(
                    "Answer generation record UPSERTED with status={} for transactionId={}",
                    status, transactionId);

        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(ERROR_MESSAGE, "UPSERT", transactionId), e);
        }
    }

    // ---------------------------------------------------------------------
    // RETRIEVE
    // ---------------------------------------------------------------------

    public TableEntity getByTransactionId(final String transactionId)
            throws EntityRetrievalException {

        final String rowAndPartitionKey =
                generateKeyForRowAndPartition(transactionId);

        try {
            return tableClient.getEntity(
                    rowAndPartitionKey,
                    rowAndPartitionKey);

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_NOT_FOUND
                    || tse.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw new EntityRetrievalException(
                    "Failed to retrieve answer generation record for transactionId "
                            + transactionId,
                    tse);
        } catch (Exception e) {
            throw new EntityRetrievalException(
                    "Failed to retrieve answer generation record for transactionId "
                            + transactionId,
                    e);
        }
    }

    // ---------------------------------------------------------------------
    // INTERNALS
    // ---------------------------------------------------------------------

    private TableEntity buildEntity(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final String chunkedEntries,
            final String llmResponse,
            final AnswerGenerationStatus status,
            final String reason,
            final Long responseGenerationDuration
    ) {

        final String rowAndPartitionKey =
                generateKeyForRowAndPartition(transactionId);

        final TableEntity entity =
                new TableEntity(rowAndPartitionKey, rowAndPartitionKey);

        entity.addProperty(TC_TRANSACTION_ID, transactionId);
        entity.addProperty(TC_USER_QUERY, userQuery);
        entity.addProperty(TC_QUERY_PROMPT, queryPrompt);
        entity.addProperty(TC_CHUNKED_ENTRIES, chunkedEntries);
        entity.addProperty(TC_LLM_RESPONSE, llmResponse);
        entity.addProperty(TC_ANSWER_STATUS, status.name());
        entity.addProperty(TC_REASON, reason);
        entity.addProperty(
                TC_RESPONSE_GENERATION_DURATION,
                responseGenerationDuration);

        return entity;
    }
}
