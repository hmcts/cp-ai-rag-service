package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import org.junit.jupiter.api.Test;

class AiGatewayBackendTest {

    private static final String BASE_URL = "https://gateway.example.test/ai/platform/v1/bedrock-messages";

    private static HttpRequest messagesRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl(BASE_URL)
                .addPathSegments("v1", "messages")
                .build();
    }

    @Test
    void stripsTrailingSlashFromBaseUrl() {
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL + "/", () -> "token", "key");

        assertEquals(BASE_URL, backend.baseUrl());
    }

    @Test
    void addsAnthropicVersionWhenAbsent() {
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL, () -> "token", "key");

        final HttpRequest prepared = backend.prepareRequest(messagesRequest());

        assertEquals(List.of("2023-06-01"), prepared.headers().values("anthropic-version"));
    }

    @Test
    void keepsAnthropicVersionAlreadySet() {
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL, () -> "token", "key");
        final HttpRequest request = messagesRequest().toBuilder().putHeader("anthropic-version", "2099-01-01").build();

        assertEquals(List.of("2099-01-01"), backend.prepareRequest(request).headers().values("anthropic-version"));
    }

    @Test
    void addsBearerTokenAndSubscriptionKey() {
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL, () -> "token-1", "sub-key");

        final HttpRequest authorized = backend.authorizeRequest(messagesRequest());

        assertEquals(List.of("Bearer token-1"), authorized.headers().values("Authorization"));
        assertEquals(List.of("sub-key"), authorized.headers().values("Ocp-Apim-Subscription-Key"));
    }

    @Test
    void fetchesTokenOnEveryRequestSoItCanRefresh() {
        final AtomicInteger calls = new AtomicInteger();
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL, () -> "token-" + calls.incrementAndGet(), "key");

        backend.authorizeRequest(messagesRequest());
        final HttpRequest second = backend.authorizeRequest(messagesRequest());

        assertEquals(List.of("Bearer token-2"), second.headers().values("Authorization"));
    }

    @Test
    void keepsAuthorizationAlreadySet() {
        final AiGatewayBackend backend = new AiGatewayBackend(BASE_URL, () -> "token", "key");
        final HttpRequest request = messagesRequest().toBuilder().putHeader("Authorization", "Bearer preset").build();

        assertEquals(List.of("Bearer preset"), backend.authorizeRequest(request).headers().values("Authorization"));
    }

    @Test
    void rejectsMissingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AiGatewayBackend("", () -> "token", "key"));
        assertThrows(IllegalArgumentException.class, () -> new AiGatewayBackend(BASE_URL, () -> "token", ""));
        assertThrows(NullPointerException.class, () -> new AiGatewayBackend(BASE_URL, null, "key"));
    }
}
