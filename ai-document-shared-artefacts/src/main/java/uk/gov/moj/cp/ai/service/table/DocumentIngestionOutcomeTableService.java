package uk.gov.moj.cp.ai.service.table;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_METADATA;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_SUPERSEDED_DOCUMENTS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_EXPIRES_AT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_OWNER;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TIMESTAMP;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.idempotency.IdempotencyStatusStore;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;

import java.time.OffsetDateTime;
import java.util.Objects;

import com.azure.data.tables.models.TableEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentIngestionOutcomeTableService implements IdempotencyStatusStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionOutcomeTableService.class);

    private final TableService tableService;

    public DocumentIngestionOutcomeTableService(String tableName) {
        if (isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name cannot be null or empty.");
        }

        this.tableService = new TableService(tableName);

    }

    protected DocumentIngestionOutcomeTableService(final TableService tableService) {
        this.tableService = tableService;
    }

    public void insert(final String clientId, final String documentId, final String documentName, final String metadata, final String supersededDocuments,
                       final String status, final String reason) throws DuplicateRecordException {
        final TableEntity entity = new TableEntity(partitionKey(clientId, documentId), documentId);
        entity.addProperty(TC_DOCUMENT_FILE_NAME, documentName);
        entity.addProperty(TC_DOCUMENT_ID, documentId);
        entity.addProperty(TC_DOCUMENT_METADATA, metadata);
        entity.addProperty(TC_DOCUMENT_SUPERSEDED_DOCUMENTS, supersededDocuments);
        entity.addProperty(TC_DOCUMENT_STATUS, status);
        entity.addProperty(TC_REASON, reason);

        tableService.insertIntoTable(entity);

        LOGGER.info("Document upload record INSERTED into table with status '{}' for document '{}' with ID '{}'", status, documentName, documentId);
    }

    public void upsertDocument(final String clientId, final String documentId, final String status, final String reason) {

        try {
            final TableEntity entity = tableService.getFirstDocumentMatching(partitionKey(clientId, documentId), documentId);

            entity.addProperty(TC_DOCUMENT_STATUS, status);
            entity.addProperty(TC_REASON, reason);
            tableService.upsertIntoTable(entity);

            LOGGER.info("Record UPSERTED into table with status={} for document with ID '{}'", status, documentId);

        } catch (Exception e) {
            throw new RuntimeException(format("Failed to %s record for document with ID: '%s", "UPSERT", documentId), e);
        }
    }

    /**
     * Fenced terminal write for the ingestion worker: conditionally MERGEs only the status and
     * reason columns (If-Match on the claim-time ETag). Unlike {@link #upsertDocument} there is
     * no read-modify-write — MERGE preserves the other columns by definition. Throws
     * {@link uk.gov.moj.cp.ai.exception.EtagMismatchException} if the lease was reclaimed.
     */
    public void recordOutcomeFenced(final String clientId, final String documentId, final String status, final String reason, final String etag) {
        final TableEntity entity = new TableEntity(partitionKey(clientId, documentId), documentId);
        entity.addProperty(TC_DOCUMENT_STATUS, status);
        entity.addProperty(TC_REASON, reason);

        tableService.updateEntityIfUnchanged(entity, etag);

        LOGGER.info("Record fenced-UPDATED with status={} for document with ID '{}'", status, documentId);
    }

    @Override
    public LeaseSnapshot readForClaim(final String clientId, final String key) throws EntityRetrievalException {
        final TableEntity entity = tableService.getFirstDocumentMatching(partitionKey(clientId, key), key);
        if (null == entity) {
            return null;
        }
        return new LeaseSnapshot(
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_STATUS)),
                entity.getETag(),
                (OffsetDateTime) entity.getProperty(TC_LEASE_EXPIRES_AT),
                getPropertyAsString(entity.getProperty(TC_LEASE_OWNER))
        );
    }

    @Override
    public boolean isTerminal(final String status) {
        return DocumentIngestionStatus.INGESTION_SUCCESS.name().equals(status)
                || DocumentIngestionStatus.INGESTION_FAILED.name().equals(status)
                || DocumentIngestionStatus.FILE_SIZE_OVER_LIMIT.name().equals(status);
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
        entity.addProperty(TC_DOCUMENT_ID, key);
        entity.addProperty(TC_DOCUMENT_STATUS, DocumentIngestionStatus.AWAITING_INGESTION.name());
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
            LOGGER.warn("Best-effort lease release failed for documentId={} — lease will expire by TTL", key, e);
        }
    }

    public DocumentIngestionOutcome getDocumentById(final String clientId, final String documentId) throws EntityRetrievalException {
        final TableEntity entity = tableService.getFirstDocumentMatching(partitionKey(clientId, documentId), documentId);
        if (null == entity) {
            return null;
        }
        return getDocumentIngestionOutcome(entity);
    }

    /**
     * Effective partition key for a {@code (clientId, key)} row. When a {@code clientId} is present
     * it becomes the partition, isolating rows per client; a null or blank {@code clientId} falls
     * back to the row key as the partition (legacy PK == RK == key layout).
     */
    private static String partitionKey(final String clientId, final String key) {
        return isNullOrEmpty(clientId) ? key : clientId;
    }

    private String getPropertyAsString(final Object value) {
        if (Objects.nonNull(value)) {
            return value.toString();
        }
        return null;
    }

    private DocumentIngestionOutcome getDocumentIngestionOutcome(final TableEntity entity) {
        return new DocumentIngestionOutcome(
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_ID)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_FILE_NAME)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_METADATA)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_SUPERSEDED_DOCUMENTS)),
                getPropertyAsString(entity.getProperty(TC_DOCUMENT_STATUS)),
                getPropertyAsString(entity.getProperty(TC_REASON)),
                getPropertyAsString(entity.getProperty(TC_TIMESTAMP)));
    }

}