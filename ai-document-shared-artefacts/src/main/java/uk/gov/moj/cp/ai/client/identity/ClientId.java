package uk.gov.moj.cp.ai.client.identity;

import uk.gov.moj.cp.ai.util.UuidUtil;

/**
 * Worker-side helper to defensively re-validate a payload-carried {@code clientId} using the same
 * UUID-shape check ({@code UuidUtil.isValid}) and the same exception type ({@link ClientIdentityException})
 * as the HTTP boundary, so queue workers (wired in MTDI-06) have one shared validation routine (FR-1/NFR-4).
 *
 * <p>Skeleton for MTDI-01 — {@link #requireValid(String)} is unimplemented and throws; the
 * implementation story makes it green.
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
        if (!UuidUtil.isValid(clientId)) {
            throw new ClientIdentityException("Missing or invalid client identity");
        }
        return clientId;
    }
}
