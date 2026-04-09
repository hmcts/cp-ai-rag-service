package uk.gov.moj.cp.ai.exception;

@SuppressWarnings("java:S118")
public class ScoringServiceException extends RuntimeException {

    public ScoringServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
