package uk.gov.moj.cp.metadata.check.exception;

public class QueueSendException extends RuntimeException {
    public QueueSendException(final String message) {
        super(message);
    }
}
