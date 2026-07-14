package uk.gov.moj.cp.retrieval.exception;

/**
 * Thrown to fail the invocation on purpose so the queue redelivers the message
 * (citation-degraded retries, transient work failures, lease conflicts). Typed so
 * outer handlers can propagate an already-made redelivery decision without re-wrapping
 * it (which would lose the message qualifier).
 */
public class RedeliveryException extends RuntimeException {

    public RedeliveryException(final String message, final Exception cause) {
        super(message, cause);
    }
}
