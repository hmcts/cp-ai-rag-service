package uk.gov.moj.cp.ai.exception;

public class EmbeddingServiceException extends Exception {

    public EmbeddingServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
