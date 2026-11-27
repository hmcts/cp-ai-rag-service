package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.anthropic.backends.Backend;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic SDK {@link Backend} for Claude served through the AI gateway's Messages API route.
 *
 * <p>The gateway exposes the Anthropic Messages API unchanged: the SDK's {@code /v1/messages} path
 * is appended to the gateway base URL and the {@code model} stays in the request body. Each request
 * carries two credentials:
 * <ul>
 *   <li>{@code Authorization: Bearer <token>} — an Entra token for the gateway's app registration,
 *       fetched from the supplier on every request so it can refresh;</li>
 *   <li>{@code Ocp-Apim-Subscription-Key} — the subscription key issued for this application.</li>
 * </ul>
 *
 * <p>Every non-2xx response is logged with the gateway's {@code x-correlation-id}, which the gateway
 * team asks for when investigating a failure. A server-directed retry wait ({@code Retry-After} or
 * {@code Retry-After-Ms}) longer than {@link #MAX_RETRY_DELAY} is shortened to it before the SDK
 * reads it: the SDK otherwise waits for whatever the server asks, and the gateway sends 60 seconds
 * on every rate or quota rejection, so three retries idled for three minutes and outlasted the queue
 * message's visibility timeout. The cap matches the OpenAI SDK's fixed ceiling, so both model clients
 * back off the same way.
 *
 * <p>The SDK's Foundry backend cannot be reused here: it appends {@code /anthropic} to any base URL
 * that lacks it, which is not the gateway's path.
 */
public final class AiGatewayBackend implements Backend {

    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String HEADER_SUBSCRIPTION_KEY = "Ocp-Apim-Subscription-Key";
    static final String HEADER_VERSION = "anthropic-version";
    static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Response header the gateway sets on every response to identify the request in its logs. */
    public static final String HEADER_CORRELATION_ID = "x-correlation-id";
    static final String HEADER_RETRY_AFTER = "Retry-After";
    static final String HEADER_RETRY_AFTER_MS = "Retry-After-Ms";
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(8);

    private static final Logger LOGGER = LoggerFactory.getLogger(AiGatewayBackend.class);

    private final String baseUrl;
    private final Supplier<String> bearerTokenSupplier;
    private final String subscriptionKey;
    private final Duration maxRetryDelay;
    private final Clock clock;

    public AiGatewayBackend(final String baseUrl, final Supplier<String> bearerTokenSupplier, final String subscriptionKey) {
        this(baseUrl, bearerTokenSupplier, subscriptionKey, MAX_RETRY_DELAY, Clock.systemUTC());
    }

    AiGatewayBackend(final String baseUrl, final Supplier<String> bearerTokenSupplier, final String subscriptionKey,
                     final Duration maxRetryDelay, final Clock clock) {
        validateNullOrEmpty(baseUrl, "AI gateway base URL must be set.");
        validateNullOrEmpty(subscriptionKey, "AI gateway subscription key must be set.");
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.bearerTokenSupplier = Objects.requireNonNull(bearerTokenSupplier, "bearerTokenSupplier");
        this.subscriptionKey = subscriptionKey;
        this.maxRetryDelay = Objects.requireNonNull(maxRetryDelay, "maxRetryDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The gateway's correlation ID from {@code headers}, or {@code "(none)"} when it is absent. */
    public static String correlationId(final Headers headers) {
        return headers.values(HEADER_CORRELATION_ID).stream().findFirst().orElse("(none)");
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public HttpRequest prepareRequest(final HttpRequest request) {
        if (request.headers().names().contains(HEADER_VERSION)) {
            return request;
        }
        return request.toBuilder().putHeader(HEADER_VERSION, ANTHROPIC_VERSION).build();
    }

    @Override
    public HttpRequest authorizeRequest(final HttpRequest request) {
        final HttpRequest.Builder builder = request.toBuilder();
        if (!request.headers().names().contains(HEADER_AUTHORIZATION)) {
            builder.putHeader(HEADER_AUTHORIZATION, "Bearer " + bearerTokenSupplier.get());
        }
        if (!request.headers().names().contains(HEADER_SUBSCRIPTION_KEY)) {
            builder.putHeader(HEADER_SUBSCRIPTION_KEY, subscriptionKey);
        }
        return builder.build();
    }

    @Override
    public HttpResponse prepareResponse(final HttpResponse response) {
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        final Optional<Duration> requested = requestedRetryDelay(response.headers());
        if (requested.isEmpty() || requested.get().compareTo(maxRetryDelay) <= 0) {
            LOGGER.warn("AI gateway returned HTTP {} (x-correlation-id: {}, requested retry wait: {} ms)",
                    status, correlationId(response.headers()), requested.map(Duration::toMillis).orElse(null));
            return response;
        }
        LOGGER.warn("AI gateway returned HTTP {} (x-correlation-id: {}); requested retry wait of {} ms capped to {} ms",
                status, correlationId(response.headers()), requested.get().toMillis(), maxRetryDelay.toMillis());
        final Headers capped = response.headers().toBuilder()
                .remove(HEADER_RETRY_AFTER)
                .replace(HEADER_RETRY_AFTER_MS, String.valueOf(maxRetryDelay.toMillis()))
                .build();
        return new HeadersReplacedResponse(response, capped);
    }

    /**
     * The wait the server asked for, read the way the SDK reads it: {@code Retry-After-Ms} first,
     * then {@code Retry-After} as seconds or an HTTP date. Empty when neither header parses.
     */
    Optional<Duration> requestedRetryDelay(final Headers headers) {
        final Optional<Duration> millis = headers.values(HEADER_RETRY_AFTER_MS).stream().findFirst()
                .flatMap(AiGatewayBackend::parseNumber)
                .map(value -> Duration.ofNanos((long) (value * 1_000_000)));
        if (millis.isPresent()) {
            return millis;
        }
        return headers.values(HEADER_RETRY_AFTER).stream().findFirst().flatMap(value -> parseNumber(value)
                .map(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000)))
                .or(() -> parseHttpDate(value)));
    }

    private static Optional<Double> parseNumber(final String value) {
        try {
            final double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<Duration> parseHttpDate(final String value) {
        try {
            return Optional.of(Duration.between(OffsetDateTime.now(clock),
                    OffsetDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)));
        } catch (final DateTimeParseException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        // Holds no resources: the token supplier and HTTP client are owned elsewhere.
    }

    /** The same response with different headers; the body and closing stay with the original. */
    private static final class HeadersReplacedResponse implements HttpResponse {

        private final HttpResponse delegate;
        private final Headers headers;

        HeadersReplacedResponse(final HttpResponse delegate, final Headers headers) {
            this.delegate = delegate;
            this.headers = headers;
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public Headers headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return delegate.body();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
