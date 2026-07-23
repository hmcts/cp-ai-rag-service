package uk.gov.moj.cp.ai.client.identity;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.util.UuidUtil;

/**
 * Worker-side helper to defensively re-validate a payload-carried {@code clientId} using the same
 * UUID-shape check ({@code UuidUtil.isValid}) and the same exception type ({@link ClientIdentityException})
 * as the HTTP boundary, so queue workers (when they adopt it) have one shared validation routine.
 */
public final class ClientId {

    private ClientId() {
        // Utility class
    }

    /**
     * @return the {@code clientId} unchanged when it is a valid UUID.
     * @throws ClientIdentityException when {@code clientId} is null, blank or not a valid UUID.
     */
    public static String requireValid(final String clientId) {
        if (!UuidUtil.isValidQuietly(clientId)) {
            throw new ClientIdentityException("Missing or invalid client identity");
        }
        return clientId;
    }

    /** Legacy null/empty clientId stays null; a present one is validated as a UUID before use. */
    public static String requireValidOrNull(final String clientId) {
        return isNullOrEmpty(clientId) ? null : requireValid(clientId);
    }
}
