package uk.gov.moj.cp.ai.exception;

public class DuplicateRecordException extends Exception {

    public DuplicateRecordException(final String message) {
        super(message);
    }

    public DuplicateRecordException(final String message, final Exception e) {
        super(message, e);
    }
}
