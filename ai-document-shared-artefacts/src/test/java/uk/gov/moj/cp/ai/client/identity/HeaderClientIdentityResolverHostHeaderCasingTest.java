package uk.gov.moj.cp.ai.client.identity;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Integration-style unit specs for the header-casing contract: the caller (via APIM) sets the
 * identity header using its canonical, mixed-case name, but the Functions host presents request
 * headers to {@code getHeaders()} under lower-cased keys. Resolution must succeed against the real,
 * host-shaped header map regardless of the configured header's casing.
 */
@ExtendWith(MockitoExtension.class)
class HeaderClientIdentityResolverHostHeaderCasingTest {

    @Mock
    private HttpRequestMessage<Optional<String>> request;

    @Test
    @DisplayName("resolves against the host's lower-cased key when configured with the canonical mixed-case header name")
    void shouldResolveAgainstLowerCasedKey_whenConfiguredWithCanonicalHeaderName() {
        final String clientId = randomUUID().toString();
        // The host-shaped map carries several lower-cased headers, exactly as the Functions runtime
        // exposes them; the identity header key is lower-cased even though the caller sent it as X-Client-Id.
        when(request.getHeaders()).thenReturn(Map.of(
                "content-type", "application/json",
                "x-client-id", clientId,
                "accept", "application/json"));

        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver("X-Client-Id", true);

        assertEquals(clientId, resolver.resolve(request).clientId().orElseThrow());
    }

    @Test
    @DisplayName("resolves against the host's lower-cased key for a custom, upper-cased configured header name")
    void shouldResolveAgainstLowerCasedKey_whenConfiguredHeaderNameIsUpperCased() {
        final String clientId = randomUUID().toString();
        when(request.getHeaders()).thenReturn(Map.of("x-consumer-id", clientId));

        final HeaderClientIdentityResolver resolver = new HeaderClientIdentityResolver("X-CONSUMER-ID", true);

        assertEquals(clientId, resolver.resolve(request).clientId().orElseThrow());
    }
}
