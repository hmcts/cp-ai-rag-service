package uk.gov.moj.cp.ai.idempotency;

import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Flow-specific persistence operations the {@link IdempotencyGuard} needs against a status
 * table whose rows are keyed PartitionKey == RowKey == key. Implemented by the existing
 * status-table services.
 */
public interface IdempotencyStatusStore {

    /**
     * Written to {@code LeaseExpiresAt} on release. Azure Tables MERGE cannot remove a
     * property, so an epoch timestamp marks the lease as immediately reclaimable.
     */
    OffsetDateTime LEASE_RELEASED = Instant.EPOCH.atOffset(ZoneOffset.UTC);

    /** Current status/etag/lease state for the key, or {@code null} if the row is missing. */
    LeaseSnapshot readForClaim(String key) throws EntityRetrievalException;

    /** Whether this status value means the work is already done (success or exhausted failure). */
    boolean isTerminal(String status);

    /**
     * Conditionally writes the lease columns (If-Match on {@code expectedEtag}).
     *
     * @return the row's new ETag — the winner's fencing token
     * @throws EtagMismatchException if another worker changed the row first (claim lost)
     */
    String claimLease(String key, String expectedEtag, String owner, OffsetDateTime expiresAt);

    /**
     * Defensive path for a missing status row: creates a minimal non-terminal row with the
     * lease already applied, so the execution is still fenced.
     *
     * @return the created row's ETag
     */
    String createClaimedRow(String key, String owner, OffsetDateTime expiresAt) throws DuplicateRecordException;

    /**
     * Best-effort release: marks the lease reclaimable ({@link #LEASE_RELEASED}) via a
     * conditional write on {@code etag}. A rejection (someone reclaimed) or any other
     * failure is swallowed — the lease then simply expires by TTL.
     */
    void releaseLease(String key, String etag);
}
