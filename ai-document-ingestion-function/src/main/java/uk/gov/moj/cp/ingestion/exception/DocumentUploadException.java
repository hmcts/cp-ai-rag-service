package uk.gov.moj.cp.ingestion.exception;

public class DocumentUploadException extends RuntimeException {

    public DocumentUploadException(String message) {
        super(message);
    }

    public DocumentUploadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
