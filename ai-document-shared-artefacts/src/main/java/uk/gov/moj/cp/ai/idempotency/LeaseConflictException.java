package uk.gov.moj.cp.ai.idempotency;

/**
 * Another worker holds a live in-progress lease on the key (or won the claim race).
 * The caller should rethrow a retryable exception so the message redelivers and
 * re-checks the row later — by then the leaseholder has either completed (terminal
 * skip) or its lease has expired (reclaimable).
 */
public class LeaseConflictException extends RuntimeException {

    public LeaseConflictException(final String message) {
        super(message);
    }

    public LeaseConflictException(final String message, final Exception e) {
        super(message, e);
    }
}
