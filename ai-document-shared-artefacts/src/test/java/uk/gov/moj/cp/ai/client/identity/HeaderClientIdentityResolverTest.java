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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * MTDI-01 red-phase specs for {@link HeaderClientIdentityResolver}. Encodes AC-001..AC-006.
 *
 * <p>Strictness is LENIENT so that header stubs — which the resolver only consumes once
 * {@code resolve(...)} is implemented — do not raise UnnecessaryStubbingException while the skeleton
 * throws {@code UnsupportedOperationException}. Every test here is expected to FAIL until MTDI-01 is
 * implemented; the failures are the intended reds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
        when(request.getHeaders()).thenReturn(Map.of(LOWER_HEADER, randomUUID().toString()));
        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver(HEADER, false);

        final ClientContext ctx = resolver.resolve(request);

        assertFalse(ctx.enforced());
        assertTrue(ctx.clientId().isEmpty());
    }

    @Test
    @DisplayName("AC-004: flag off + header absent → unenforced context, never throws")
    void shouldReturnUnenforced_whenFlagOffAndHeaderAbsent() {
        when(request.getHeaders()).thenReturn(Map.of());
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
}
