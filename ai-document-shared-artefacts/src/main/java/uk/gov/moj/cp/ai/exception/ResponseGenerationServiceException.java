package uk.gov.moj.cp.ai.exception;

public class ResponseGenerationServiceException extends RuntimeException {

    public ResponseGenerationServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
