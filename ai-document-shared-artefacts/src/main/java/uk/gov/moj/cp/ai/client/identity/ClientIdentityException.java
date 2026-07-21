package uk.gov.moj.cp.ai.client.identity;

/**
 * Thrown when {@code CLIENT_FILTERING_ENABLED} enforcement is on and the caller's client identity is
 * missing, blank or not a valid UUID. Callers map this to HTTP 401 (mapping added when HTTP functions adopt it).
 *
 * <p>Also thrown by the worker-side {@link ClientId#requireValid(String)} guard so both the HTTP
 * boundary and queue workers reject an invalid {@code clientId} with one shared exception type.
 */
public class ClientIdentityException extends RuntimeException {

    public ClientIdentityException(final String message) {
        super(message);
    }

    public ClientIdentityException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
