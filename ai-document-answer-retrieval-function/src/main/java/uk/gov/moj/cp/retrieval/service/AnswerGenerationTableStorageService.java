package uk.gov.moj.cp.retrieval.service;

import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_TIME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus;

import java.time.OffsetDateTime;
import java.util.List;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
    public void saveAnswerGenerationRequest(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final AnswerGenerationStatus status
    ) throws DuplicateRecordException {
        insertIntoTable(transactionId, userQuery, queryPrompt, null, null, status, null, null, null);
    }

    public void insertIntoTable(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final String chunkedEntries,
            final String llmResponse,
            final AnswerGenerationStatus status,
            final String reason,
            final OffsetDateTime responseGenerationTime,
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
                    responseGenerationTime,
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
            final OffsetDateTime responseGenerationTime,
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
                    responseGenerationTime,
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

    public GeneratedAnswer getGeneratedAnswer(final String transactionId) throws EntityRetrievalException {
        try {

            final TableEntity entity = tableClient.getEntity(transactionId, transactionId);
            return new GeneratedAnswer(
                    getPropertyAsString(entity.getProperty(TC_TRANSACTION_ID)),
                    getPropertyAsString(entity.getProperty(TC_USER_QUERY)),
                    getPropertyAsString(entity.getProperty(TC_QUERY_PROMPT)),
                    toChunkedEntries(getPropertyAsString(entity.getProperty(TC_CHUNKED_ENTRIES))),
                    getPropertyAsString(entity.getProperty(TC_ANSWER_STATUS)),
                    getPropertyAsString(entity.getProperty(TC_REASON)),
                    (OffsetDateTime) entity.getProperty(TC_RESPONSE_GENERATION_TIME),
                    getPropertyAsLong(entity.getProperty(TC_RESPONSE_GENERATION_DURATION))
            );

        } catch (final TableServiceException tse) {
            if (tse.getValue().getErrorCode() == TableErrorCode.ENTITY_NOT_FOUND
                    || tse.getValue().getErrorCode() == TableErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw new EntityRetrievalException("Failed to retrieve answer generation record for transactionId " + transactionId, tse);
        } catch (Exception e) {
            throw new EntityRetrievalException("Failed to retrieve answer generation record for transactionId " + transactionId, e);
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
            final OffsetDateTime responseGenerationTime,
            final Long responseGenerationDuration
    ) {
        final TableEntity entity = new TableEntity(transactionId, transactionId);

        entity.addProperty(TC_TRANSACTION_ID, transactionId);
        entity.addProperty(TC_USER_QUERY, userQuery);
        entity.addProperty(TC_QUERY_PROMPT, queryPrompt);
        entity.addProperty(TC_CHUNKED_ENTRIES, chunkedEntries);
        entity.addProperty(TC_LLM_RESPONSE, llmResponse);
        entity.addProperty(TC_ANSWER_STATUS, status.name());
        entity.addProperty(TC_REASON, reason);
        entity.addProperty(TC_RESPONSE_GENERATION_TIME, responseGenerationTime);
        entity.addProperty(TC_RESPONSE_GENERATION_DURATION, responseGenerationDuration);

        return entity;
    }

    private String getPropertyAsString(final Object value) {
        return nonNull(value) ? value.toString() : null;
    }

    private Long getPropertyAsLong(final Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private List<ChunkedEntry> toChunkedEntries(final String chunkedEntriesAsString) throws JsonProcessingException {
        if (nonNull(chunkedEntriesAsString)) {
            return getObjectMapper().readValue(
                    chunkedEntriesAsString,
                    new TypeReference<List<ChunkedEntry>>() {
                    });
        }
        return null;
    }
}
