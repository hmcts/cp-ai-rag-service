package uk.gov.moj.cp.ai.exception;

public class ChatServiceException extends Exception {

    public ChatServiceException(final String message) {
        super(message);
    }

    public ChatServiceException(final String message, final Exception e) {
        super(message, e);
    }
}
