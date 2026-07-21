package uk.gov.moj.cp.ai.client.identity;

import java.util.Optional;

/**
 * Immutable result of resolving the caller's client identity at the HTTP boundary.
 *
 * <ul>
 *   <li>{@link #unenforced()} — {@code CLIENT_FILTERING_ENABLED} off: no clientId, {@code enforced()==false}.</li>
 *   <li>{@link #of(String)} — enforcement on and a valid clientId resolved: {@code enforced()==true}.</li>
 * </ul>
 *
 * <p>Skeleton for MTDI-01 — factory/accessor bodies are unimplemented and throw; the implementation
 * story makes them green.
 */
public final class ClientContext {

    private final String clientId;   // null when unenforced & absent
    private final boolean enforced;

    private ClientContext(final String clientId, final boolean enforced) {
        this.clientId = clientId;
        this.enforced = enforced;
    }

    /** Flag off: identity optional, never populated. */
    public static ClientContext unenforced() {
        return new ClientContext(null, false);
    }

    /** Flag on and identity resolved. */
    public static ClientContext of(final String clientId) {
        return new ClientContext(clientId, true);
    }

    public Optional<String> clientId() {
        return Optional.ofNullable(clientId);
    }

    public boolean enforced() {
        return enforced;
    }
}
