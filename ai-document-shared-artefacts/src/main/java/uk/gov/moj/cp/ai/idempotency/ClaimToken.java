package uk.gov.moj.cp.ai.idempotency;

/**
 * Proof of a successfully claimed in-progress lease. The {@code etag} is the fencing token:
 * every terminal status write must be conditioned on it, so a worker whose lease has since
 * been reclaimed (its etag is stale) cannot overwrite the outcome.
 */
public record ClaimToken(String key, String etag) {
}
