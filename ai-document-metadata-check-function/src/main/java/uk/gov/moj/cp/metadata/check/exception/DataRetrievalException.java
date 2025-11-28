package uk.gov.moj.cp.metadata.check.exception;

public class DataRetrievalException extends RuntimeException {

    public DataRetrievalException(final String message) {
        super(message);
    }

    public DataRetrievalException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
