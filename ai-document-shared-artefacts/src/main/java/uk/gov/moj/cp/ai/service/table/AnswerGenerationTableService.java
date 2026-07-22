package uk.gov.moj.cp.ai.service.table;

import static java.util.Objects.nonNull;
import static org.slf4j.LoggerFactory.getLogger;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES_FILE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_EXPIRES_AT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_OWNER;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_TIME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GROUNDEDNESS_SCORE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.idempotency.IdempotencyStatusStore;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.azure.data.tables.models.TableEntity;
import org.slf4j.Logger;

/**
 * Table storage service for Answer Generation outcomes.
 */
public class AnswerGenerationTableService implements IdempotencyStatusStore {
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

    public void saveAnswerGenerationRequest(final String clientId, final String transactionId, final String userQuery,
                                            final String queryPrompt, final AnswerGenerationStatus status) throws DuplicateRecordException {

        final TableEntity entity = buildEntity(
                clientId, transactionId, userQuery, queryPrompt,
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
                null,
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

    public GeneratedAnswer getGeneratedAnswer(final String clientId, final String transactionId) throws EntityRetrievalException {

        final TableEntity entity = tableService.getFirstDocumentMatching(partitionKey(clientId, transactionId), transactionId);
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

    public void recordGroundednessScore(final String clientId, final String transactionId, final BigDecimal bigDecimal) {
        final TableEntity entity = new TableEntity(partitionKey(clientId, transactionId), transactionId);
        entity.addProperty(TC_RESPONSE_GROUNDEDNESS_SCORE, bigDecimal);
        tableService.upsertIntoTable(entity);
    }

    /**
     * Same write as {@link #upsertIntoTable} but conditioned on the claim-time ETag (If-Match
     * MERGE) — the fenced terminal write of the idempotency guard. Throws
     * {@link uk.gov.moj.cp.ai.exception.EtagMismatchException} if the lease was reclaimed.
     */
    public void upsertTerminalFenced(final String clientId, final String transactionId, final String userQuery,
                                     final String queryPrompt, final String chunkedEntriesFile, final String llmResponse,
                                     final AnswerGenerationStatus status, final String reason,
                                     final OffsetDateTime responseGenerationTime, final Long responseGenerationDuration,
                                     final String etag
    ) {
        final TableEntity entity = buildEntity(
                clientId,
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

        tableService.updateEntityIfUnchanged(entity, etag);

        LOGGER.info("Answer generation record fenced-UPDATED with status={} for transactionId={}", status, transactionId);
    }

    @Override
    public LeaseSnapshot readForClaim(final String clientId, final String key) throws EntityRetrievalException {
        final TableEntity entity = tableService.getFirstDocumentMatching(partitionKey(clientId, key), key);
        if (null == entity) {
            return null;
        }
        return new LeaseSnapshot(
                getPropertyAsString(entity.getProperty(TC_ANSWER_STATUS)),
                entity.getETag(),
                (OffsetDateTime) entity.getProperty(TC_LEASE_EXPIRES_AT),
                getPropertyAsString(entity.getProperty(TC_LEASE_OWNER))
        );
    }

    @Override
    public boolean isTerminal(final String status) {
        return AnswerGenerationStatus.ANSWER_GENERATED.name().equals(status)
                || AnswerGenerationStatus.ANSWER_GENERATION_FAILED.name().equals(status);
    }

    @Override
    public String claimLease(final String clientId, final String key, final String expectedEtag, final String owner, final OffsetDateTime expiresAt) {
        final TableEntity entity = new TableEntity(partitionKey(clientId, key), key);
        entity.addProperty(TC_LEASE_OWNER, owner);
        entity.addProperty(TC_LEASE_EXPIRES_AT, expiresAt);
        return tableService.updateEntityIfUnchanged(entity, expectedEtag);
    }

    @Override
    public String createClaimedRow(final String clientId, final String key, final String owner, final OffsetDateTime expiresAt) throws DuplicateRecordException {
        final TableEntity entity = new TableEntity(partitionKey(clientId, key), key);
        entity.addProperty(TC_TRANSACTION_ID, key);
        entity.addProperty(TC_ANSWER_STATUS, AnswerGenerationStatus.ANSWER_GENERATION_PENDING.name());
        entity.addProperty(TC_LEASE_OWNER, owner);
        entity.addProperty(TC_LEASE_EXPIRES_AT, expiresAt);
        return tableService.insertReturningEtag(entity);
    }

    @Override
    public void releaseLease(final String clientId, final String key, final String etag) {
        try {
            final TableEntity entity = new TableEntity(partitionKey(clientId, key), key);
            entity.addProperty(TC_LEASE_EXPIRES_AT, LEASE_RELEASED);
            tableService.updateEntityIfUnchanged(entity, etag);
        } catch (Exception e) {
            LOGGER.warn("Best-effort lease release failed for transactionId={} — lease will expire by TTL", key, e);
        }
    }

    private TableEntity buildEntity(
            final String clientId,
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
        final TableEntity entity = new TableEntity(partitionKey(clientId, transactionId), transactionId);

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

    /**
     * Effective partition key for a {@code (clientId, key)} row. When a {@code clientId} is present
     * it becomes the partition, isolating rows per client; a null or blank {@code clientId} falls
     * back to the row key as the partition (legacy PK == RK == key layout).
     */
    private static String partitionKey(final String clientId, final String key) {
        return isNullOrEmpty(clientId) ? key : clientId;
    }
}
