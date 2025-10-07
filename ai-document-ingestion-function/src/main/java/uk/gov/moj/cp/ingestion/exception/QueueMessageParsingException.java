package uk.gov.moj.cp.ingestion.exception;

public class QueueMessageParsingException extends Exception {

    public QueueMessageParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
