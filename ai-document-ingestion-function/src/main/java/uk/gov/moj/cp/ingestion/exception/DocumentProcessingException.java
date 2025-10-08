package uk.gov.moj.cp.ingestion.exception;

/**
 * Exception thrown when there's an error processing a document.
 */
public class DocumentProcessingException extends Exception {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}

