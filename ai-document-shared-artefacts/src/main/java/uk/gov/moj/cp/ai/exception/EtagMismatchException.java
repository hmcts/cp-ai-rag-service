package uk.gov.moj.cp.ai.exception;

/**
 * A conditional (If-Match) table write was rejected because the row's ETag changed
 * since it was read — another worker has updated the row (HTTP 412).
 * Unchecked so it can propagate out of work lambdas without wrapping.
 */
public class EtagMismatchException extends RuntimeException {

    public EtagMismatchException(final String message) {
        super(message);
    }

    public EtagMismatchException(final String message, final Exception e) {
        super(message, e);
    }
}
