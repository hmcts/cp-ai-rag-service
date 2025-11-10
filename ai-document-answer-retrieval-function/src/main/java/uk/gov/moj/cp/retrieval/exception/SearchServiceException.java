package uk.gov.moj.cp.retrieval.exception;

public class SearchServiceException extends Exception {

    public SearchServiceException(final String message) {
        super(message);
    }

    public SearchServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
