package uk.gov.moj.cp.ai.exception;

public class BlobParsingException extends Exception {

    public BlobParsingException(final String message) {
        super(message);
    }

    public BlobParsingException(final String message, final Exception e) {
        super(message, e);
    }
}
