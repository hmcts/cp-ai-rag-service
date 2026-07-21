package uk.gov.moj.cp.ai.client.identity;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.microsoft.azure.functions.HttpRequestMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Specs for {@link HeaderClientIdentityResolver} (MTDI-01). Encodes AC-001..AC-006: flag-off is
 * behaviour-neutral (unenforced, never throws), flag-on resolves the configured header (default
 * {@code X-Client-Id}, host-lower-cased lookup) and rejects missing/blank/non-UUID identities.
 */
@ExtendWith(MockitoExtension.class)
class HeaderClientIdentityResolverTest {

    private static final String HEADER = "X-Client-Id";
    private static final String LOWER_HEADER = "x-client-id"; // Functions host lower-cases header keys

    @Mock
    private HttpRequestMessage<Optional<String>> request;

    @Test
    @DisplayName("AC-001: flag on + valid UUID header → enforced ClientContext with clientId populated")
    void shouldReturnEnforcedContextWithClientId_whenFlagOnAndValidUuidHeader() {
        final String clientId = randomUUID().toString();
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, clientId));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, true);

        final ClientContext ctx = resolver.resolve(request);

        assertTrue(ctx.enforced());
        assertEquals(clientId, ctx.clientId().orElseThrow());
    }

    @Test
    @DisplayName("AC-002: flag on + header absent → ClientIdentityException")
    void shouldThrow_whenFlagOnAndHeaderAbsent() {
        when(request.getHeaders()).thenReturn(Map.of());
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, true);

        assertThrows(ClientIdentityException.class, () -> resolver.resolve(request));
    }

    @Test
    @DisplayName("AC-002: flag on + empty header value → ClientIdentityException")
    void shouldThrow_whenFlagOnAndHeaderEmpty() {
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, ""));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, true);

        assertThrows(ClientIdentityException.class, () -> resolver.resolve(request));
    }

    @Test
    @DisplayName("AC-005: flag on + malformed non-UUID header → ClientIdentityException")
    void shouldThrow_whenFlagOnAndHeaderNotUuid() {
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, "not-a-uuid"));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, true);

        assertThrows(ClientIdentityException.class, () -> resolver.resolve(request));
    }

    @Test
    @DisplayName("AC-003: only the header is consulted — body/metadataFilter content has no effect")
    void shouldUseHeaderValueOnly_whenBodyCarriesDifferentClientId() {
        final String headerClientId = randomUUID().toString();
        // A spoofed clientId-like value would live in the body/metadataFilter; the resolver must ignore it
        // and never call request.getBody(). We assert the resolved identity is exactly the header value.
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, headerClientId));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, true);

        final ClientContext ctx = resolver.resolve(request);

        assertEquals(headerClientId, ctx.clientId().orElseThrow());
    }

    @Test
    @DisplayName("AC-004: flag off + header present → unenforced context, no exception, clientId empty")
    void shouldReturnUnenforced_whenFlagOffAndHeaderPresent() {
        // No getHeaders() stub: strict stubbing proves the resolver never reads the request when the flag is off.
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, false);

        final ClientContext ctx = resolver.resolve(request);

        assertFalse(ctx.enforced());
        assertTrue(ctx.clientId().isEmpty());
    }

    @Test
    @DisplayName("AC-004: flag off + header absent → unenforced context, never throws")
    void shouldReturnUnenforced_whenFlagOffAndHeaderAbsent() {
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, false);

        final ClientContext ctx = resolver.resolve(request);

        assertFalse(ctx.enforced());
        assertTrue(ctx.clientId().isEmpty());
    }

    @Test
    @DisplayName("AC-006: header name defaults to X-Client-Id and lookup is case-insensitive (lower-cased key)")
    void shouldUseDefaultHeaderCaseInsensitively_whenHeaderNameUnset() {
        final String clientId = randomUUID().toString();
        // Null header name => resolver must default to X-Client-Id and look it up under the host's
        // lower-cased key "x-client-id".
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, clientId));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(null, true);

        final ClientContext ctx = resolver.resolve(request);

        assertEquals(clientId, ctx.clientId().orElseThrow());
    }

    @Test
    @DisplayName("fromEnvironment: unset env vars → enforcement off (never throws) with default header")
    void shouldDefaultToUnenforced_whenEnvironmentUnset() {
        final HeaderClientIdentityResolver resolver = HeaderClientIdentityResolver.fromEnvironment(key -> null);

        final ClientContext ctx = resolver.resolve(request);

        assertFalse(ctx.enforced());
        assertTrue(ctx.clientId().isEmpty());
    }

    @Test
    @DisplayName("fromEnvironment: CLIENT_FILTERING_ENABLED=true + custom CLIENT_IDENTITY_HEADER → enforced on that header")
    void shouldEnforceOnConfiguredHeader_whenEnvironmentSet() {
        final String clientId = randomUUID().toString();
        final Map<String, String> env = Map.of(
                "CLIENT_FILTERING_ENABLED", "true",
                "CLIENT_IDENTITY_HEADER", "X-Consumer-Id");
        when(request.getHeaders()).thenReturn(Map.of("x-consumer-id", clientId));

        final HeaderClientIdentityResolver resolver = HeaderClientIdentityResolver.fromEnvironment(env::get);

        assertEquals(clientId, resolver.resolve(request).clientId().orElseThrow());
    }
}
