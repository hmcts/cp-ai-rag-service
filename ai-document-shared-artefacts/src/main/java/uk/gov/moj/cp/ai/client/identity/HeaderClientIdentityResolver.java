package uk.gov.moj.cp.ai.client.identity;

import com.microsoft.azure.functions.HttpRequestMessage;

/**
 * Resolves client identity from a configurable request header, gated behind {@code CLIENT_FILTERING_ENABLED}.
 *
 * <p>Header name defaults to {@code X-Client-Id} ({@code CLIENT_IDENTITY_HEADER}); lookup is
 * case-insensitive because the Functions host lower-cases header keys. UUID-shape validation reuses
 * {@code UuidUtil.isValid} (NFR-4/D7).
 *
 * <p>Skeleton for MTDI-01 — {@link #resolve(HttpRequestMessage)} is unimplemented and throws; the
 * implementation story makes it green.
 */
public final class HeaderClientIdentityResolver implements ClientIdentityResolver {

    /** Default header carrying the caller's clientId when {@code CLIENT_IDENTITY_HEADER} is unset. */
    public static final String DEFAULT_CLIENT_IDENTITY_HEADER = "X-Client-Id";

    private final String headerName;   // CLIENT_IDENTITY_HEADER, defaults to X-Client-Id when null/blank
    private final boolean enforced;    // CLIENT_FILTERING_ENABLED (FR-3)

    public HeaderClientIdentityResolver(final String headerName, final boolean enforced) {
        this.headerName = headerName;
        this.enforced = enforced;
    }

    /**
     * Builds a resolver from environment configuration ({@code CLIENT_FILTERING_ENABLED},
     * {@code CLIENT_IDENTITY_HEADER}). Unimplemented in the MTDI-01 skeleton.
     */
    public static HeaderClientIdentityResolver fromEnvironment() {
        throw new UnsupportedOperationException("Not implemented — MTDI01");
    }

    @Override
    public ClientContext resolve(final HttpRequestMessage<?> request) {
        throw new UnsupportedOperationException("Not implemented — MTDI01");
    }
}
