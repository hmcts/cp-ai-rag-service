package uk.gov.moj.cp.ai.exception;

public class EnvVarNotFoundException extends RuntimeException {

    public EnvVarNotFoundException(final String message) {
        super(message);
    }

    public EnvVarNotFoundException(final String message, final Exception e) {
        super(message, e);
    }
}
