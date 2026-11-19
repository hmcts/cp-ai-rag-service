package uk.gov.moj.cp.ai.client;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Supplier;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;

/**
 * Supplies a bearer token for one scope, reusing it until shortly before it expires.
 *
 * <p>Some credentials have no token cache of their own: the Azure CLI credential starts an
 * {@code az} process for every request. Callers that fetch a token per HTTP request (as the AI
 * gateway backend does) would otherwise pay that cost on every call.
 */
final class CachingTokenSupplier implements Supplier<String> {

    static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final TokenCredential credential;
    private final TokenRequestContext context;
    private final Clock clock;

    private AccessToken cached;

    CachingTokenSupplier(final TokenCredential credential, final String scope) {
        this(credential, scope, Clock.systemUTC());
    }

    CachingTokenSupplier(final TokenCredential credential, final String scope, final Clock clock) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.context = new TokenRequestContext().addScopes(Objects.requireNonNull(scope, "scope"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized String get() {
        if (cached == null || isDueForRefresh(cached)) {
            cached = credential.getTokenSync(context);
        }
        return cached.getToken();
    }

    private boolean isDueForRefresh(final AccessToken token) {
        final OffsetDateTime expiresAt = token.getExpiresAt();
        return expiresAt == null || !OffsetDateTime.now(clock).plus(REFRESH_BEFORE_EXPIRY).isBefore(expiresAt);
    }
}
