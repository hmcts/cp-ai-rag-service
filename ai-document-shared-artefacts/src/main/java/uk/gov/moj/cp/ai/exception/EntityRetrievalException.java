package uk.gov.moj.cp.ai.exception;

public class EntityRetrievalException extends Exception {

    public EntityRetrievalException(final String message) {
        super(message);
    }

    public EntityRetrievalException(final String message, final Exception e) {
        super(message, e);
    }
}
