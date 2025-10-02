package uk.gov.moj.cp.ai;

public class EmbeddingServiceException extends Exception {

    public EmbeddingServiceException(final String message) {
        super(message);
    }

    public EmbeddingServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
