package uk.gov.moj.cp.ai.exception;

/**
 * A Table Storage operation (insert/upsert/conditional update) failed for a reason other than
 * the specifically modelled outcomes ({@link DuplicateRecordException}, {@link EtagMismatchException}).
 * Unchecked, mirroring the transient/unexpected nature of the underlying storage errors.
 */
public class TableOperationException extends RuntimeException {

    public TableOperationException(final String message, final Exception e) {
        super(message, e);
    }
}
