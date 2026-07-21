package uk.gov.moj.cp.ai.idempotency;

/**
 * Proof of a successfully claimed in-progress lease. The {@code etag} is the fencing token:
 * every terminal status write must be conditioned on it, so a worker whose lease has since
 * been reclaimed (its etag is stale) cannot overwrite the outcome.
 * <p>
 * {@code clientId} carries the owning namespace so a fenced terminal write targets the correct
 * partition. A null {@code clientId} means legacy keying (partition == key).
 */
public record ClaimToken(String clientId, String key, String etag) {
}
