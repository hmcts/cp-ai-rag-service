package uk.gov.moj.cp.ai.client.identity;

import static java.lang.Boolean.parseBoolean;
import static uk.gov.moj.cp.ai.SharedSystemVariables.CLIENT_FILTERING_ENABLED;
import static uk.gov.moj.cp.ai.SharedSystemVariables.CLIENT_IDENTITY_HEADER;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.util.EnvVarUtil;
import uk.gov.moj.cp.ai.util.UuidUtil;

import java.util.Locale;

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
        this.headerName = isNullOrEmpty(headerName) ? DEFAULT_CLIENT_IDENTITY_HEADER : headerName;
        this.enforced = enforced;
    }

    /**
     * Builds a resolver from environment configuration: {@code CLIENT_FILTERING_ENABLED} (default
     * {@code false}) and {@code CLIENT_IDENTITY_HEADER} (default {@code X-Client-Id}). A trivial
     * composition of the tested constructor.
     */
    public static HeaderClientIdentityResolver fromEnvironment() {
        final boolean enforced = parseBoolean(EnvVarUtil.getRequiredEnv(CLIENT_FILTERING_ENABLED, "false"));
        final String headerName = EnvVarUtil.getRequiredEnv(CLIENT_IDENTITY_HEADER, DEFAULT_CLIENT_IDENTITY_HEADER);
        return new HeaderClientIdentityResolver(headerName, enforced);
    }

    @Override
    public ClientContext resolve(final HttpRequestMessage<?> request) {
        if (!enforced) {
            return ClientContext.unenforced();                       // flag off → identity optional (AC-4)
        }
        final String raw = request.getHeaders().get(headerName.toLowerCase(Locale.ROOT)); // host lower-cases keys
        if (isNullOrEmpty(raw) || !UuidUtil.isValid(raw)) {          // UUID validated at the boundary (NFR-4/D7)
            throw new ClientIdentityException("Missing or invalid client identity");
        }
        return ClientContext.of(raw);                                // AC-1
    }
}
