package uk.gov.moj.cp.ai.exception;

public class ScoringServiceException extends RuntimeException {

    public ScoringServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
