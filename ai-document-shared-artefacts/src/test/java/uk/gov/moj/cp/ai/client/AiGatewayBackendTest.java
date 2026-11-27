package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
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

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC);

    private static AiGatewayBackend backendWithClock() {
        return new AiGatewayBackend(BASE_URL, () -> "token", "key", AiGatewayBackend.MAX_RETRY_DELAY, FIXED_CLOCK);
    }

    private static HttpResponse response(final int status, final Map<String, String> headers, final AtomicBoolean closed) {
        final Headers.Builder builder = Headers.builder();
        headers.forEach(builder::put);
        final Headers built = builder.build();
        return new HttpResponse() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public Headers headers() {
                return built;
            }

            @Override
            public InputStream body() {
                return new ByteArrayInputStream("{}".getBytes());
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static HttpResponse response(final int status, final Map<String, String> headers) {
        return response(status, headers, new AtomicBoolean());
    }

    @Test
    void leavesSuccessfulResponsesUntouched() {
        final HttpResponse ok = response(200, Map.of("Retry-After", "60"));

        assertSame(ok, backendWithClock().prepareResponse(ok));
    }

    @Test
    void leavesRetryWaitWithinTheCapUntouched() {
        final HttpResponse throttled = response(429, Map.of("Retry-After", "5"));

        assertSame(throttled, backendWithClock().prepareResponse(throttled));
    }

    @Test
    void leavesResponsesWithoutRetryWaitUntouched() {
        final HttpResponse failed = response(500, Map.of("x-correlation-id", "corr-1"));

        assertSame(failed, backendWithClock().prepareResponse(failed));
    }

    @Test
    void capsRetryAfterSeconds() {
        final HttpResponse prepared = backendWithClock().prepareResponse(
                response(429, Map.of("Retry-After", "60", "x-correlation-id", "corr-1")));

        assertEquals(429, prepared.statusCode());
        assertEquals(List.of(), prepared.headers().values("Retry-After"));
        assertEquals(List.of("8000"), prepared.headers().values("Retry-After-Ms"));
        assertEquals(List.of("corr-1"), prepared.headers().values("x-correlation-id"));
    }

    @Test
    void capsRetryAfterMilliseconds() {
        final HttpResponse prepared = backendWithClock().prepareResponse(
                response(503, Map.of("Retry-After-Ms", "30000", "Retry-After", "30")));

        assertEquals(List.of(), prepared.headers().values("Retry-After"));
        assertEquals(List.of("8000"), prepared.headers().values("Retry-After-Ms"));
    }

    @Test
    void capsRetryAfterHttpDate() {
        final HttpResponse prepared = backendWithClock().prepareResponse(
                response(429, Map.of("Retry-After", "Fri, 27 Nov 2026 13:00:00 GMT")));

        assertEquals(List.of(), prepared.headers().values("Retry-After"));
        assertEquals(List.of("8000"), prepared.headers().values("Retry-After-Ms"));
    }

    @Test
    void readsRequestedRetryDelayLikeTheSdk() {
        final AiGatewayBackend backend = backendWithClock();

        assertEquals(Optional.of(Duration.ofMillis(1500)),
                backend.requestedRetryDelay(response(429, Map.of("Retry-After-Ms", "1500", "Retry-After", "60")).headers()));
        assertEquals(Optional.of(Duration.ofMillis(2500)),
                backend.requestedRetryDelay(response(429, Map.of("Retry-After", "2.5")).headers()));
        assertEquals(Optional.of(Duration.ofSeconds(30)),
                backend.requestedRetryDelay(response(429, Map.of("Retry-After", "Fri, 27 Nov 2026 12:00:30 GMT")).headers()));
        assertEquals(Optional.empty(),
                backend.requestedRetryDelay(response(429, Map.of("Retry-After", "soon")).headers()));
    }

    @Test
    void cappedResponseKeepsBodyAndClosesTheOriginal() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final HttpResponse prepared = backendWithClock().prepareResponse(response(429, Map.of("Retry-After", "60"), closed));

        assertEquals("{}", new String(prepared.body().readAllBytes()));
        prepared.close();
        assertTrue(closed.get());
    }

    @Test
    void correlationIdFallsBackWhenAbsent() {
        assertEquals("corr-9", AiGatewayBackend.correlationId(response(500, Map.of("X-Correlation-Id", "corr-9")).headers()));
        assertEquals("(none)", AiGatewayBackend.correlationId(response(500, Map.of()).headers()));
    }
}
