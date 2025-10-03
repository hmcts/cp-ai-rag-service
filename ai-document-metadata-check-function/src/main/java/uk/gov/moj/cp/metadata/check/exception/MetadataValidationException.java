package uk.gov.moj.cp.metadata.check.exception;

public class MetadataValidationException extends RuntimeException {

    public MetadataValidationException(final String message) {
        super(message);
    }

    public MetadataValidationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
