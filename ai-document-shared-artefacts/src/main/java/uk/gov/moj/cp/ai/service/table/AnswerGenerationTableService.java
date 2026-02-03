package uk.gov.moj.cp.ai.service.table;

import static java.util.Objects.nonNull;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES_FILE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_TIME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GROUNDEDNESS_SCORE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.azure.data.tables.models.TableEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;

/**
 * Table storage service for Answer Generation outcomes.
 */
public class AnswerGenerationTableService {
    private static final Logger LOGGER = getLogger(AnswerGenerationTableService.class);

    private final TableService tableService;

    public AnswerGenerationTableService(final String tableName) {
        if (isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }
        this.tableService = new TableService(tableName);
    }

    protected AnswerGenerationTableService(final TableService tableService) {
        this.tableService = tableService;
    }

    public void saveAnswerGenerationRequest(final String transactionId, final String userQuery,
                                            final String queryPrompt, final AnswerGenerationStatus status) throws DuplicateRecordException {

        final TableEntity entity = buildEntity(
                transactionId, userQuery, queryPrompt,
                null, null, status,
                null, null, null
        );

        tableService.insertIntoTable(entity);
        LOGGER.info("Answer generation record INSERTED with status={} for transactionId={}", status, transactionId);
    }

    public void upsertIntoTable(final String transactionId, final String userQuery,
                                final String queryPrompt, final String chunkedEntriesFile, final String llmResponse,
                                final AnswerGenerationStatus status, final String reason,
                                final OffsetDateTime responseGenerationTime, final Long responseGenerationDuration
    ) {

        final TableEntity entity = buildEntity(
                transactionId,
                userQuery,
                queryPrompt,
                chunkedEntriesFile,
                llmResponse,
                status,
                reason,
                responseGenerationTime,
                responseGenerationDuration
        );

        tableService.upsertIntoTable(entity);

        LOGGER.info("Answer generation record UPSERTED with status={} for transactionId={}", status, transactionId);
    }

    public GeneratedAnswer getGeneratedAnswer(final String transactionId) throws EntityRetrievalException {

        final TableEntity entity = tableService.getFirstDocumentMatching(transactionId, transactionId);
        if (null == entity) {
            return null;
        }

        return new GeneratedAnswer(
                getPropertyAsString(entity.getProperty(TC_TRANSACTION_ID)),
                getPropertyAsString(entity.getProperty(TC_USER_QUERY)),
                getPropertyAsString(entity.getProperty(TC_QUERY_PROMPT)),
                getPropertyAsString(entity.getProperty(TC_CHUNKED_ENTRIES_FILE)),
                getPropertyAsString(entity.getProperty(TC_LLM_RESPONSE)),
                getPropertyAsString(entity.getProperty(TC_ANSWER_STATUS)),
                getPropertyAsString(entity.getProperty(TC_REASON)),
                (OffsetDateTime) entity.getProperty(TC_RESPONSE_GENERATION_TIME),
                getPropertyAsLong(entity.getProperty(TC_RESPONSE_GENERATION_DURATION))
        );

    }

    public void recordGroundednessScore(final String transactionId, final BigDecimal bigDecimal) {
        final TableEntity entity = new TableEntity(transactionId, transactionId);
        entity.addProperty(TC_RESPONSE_GROUNDEDNESS_SCORE, bigDecimal);
        tableService.upsertIntoTable(entity);
    }

    private TableEntity buildEntity(
            final String transactionId,
            final String userQuery,
            final String queryPrompt,
            final String chunkedEntriesFile,
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
        entity.addProperty(TC_CHUNKED_ENTRIES_FILE, chunkedEntriesFile);
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

    private List<ChunkedEntry> toChunkedEntries(final String chunkedEntriesAsString) {
        if (nonNull(chunkedEntriesAsString)) {
            try {
                return getObjectMapper().readValue(
                        chunkedEntriesAsString,
                        new TypeReference<>() {
                        });
            } catch (JsonProcessingException e) {
                LOGGER.error("Unable to parse chunked entries from string", e);
                return List.of();
            }
        }
        return null;
    }
}
