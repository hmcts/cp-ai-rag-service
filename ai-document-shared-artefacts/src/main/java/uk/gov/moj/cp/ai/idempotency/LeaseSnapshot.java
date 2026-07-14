package uk.gov.moj.cp.ai.idempotency;

import java.time.OffsetDateTime;

/**
 * Internal read model of a status row for claim decisions: its current status, ETag,
 * and lease columns. Deliberately not the public status POJOs — the lease is internal.
 */
public record LeaseSnapshot(String status, String etag, OffsetDateTime leaseExpiresAt, String leaseOwner) {
}
