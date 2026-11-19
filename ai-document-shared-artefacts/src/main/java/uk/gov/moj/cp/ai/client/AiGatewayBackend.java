package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.Objects;
import java.util.function.Supplier;

import com.anthropic.backends.Backend;
import com.anthropic.core.http.HttpRequest;

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
 * <p>The SDK's Foundry backend cannot be reused here: it appends {@code /anthropic} to any base URL
 * that lacks it, which is not the gateway's path.
 */
public final class AiGatewayBackend implements Backend {

    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String HEADER_SUBSCRIPTION_KEY = "Ocp-Apim-Subscription-Key";
    static final String HEADER_VERSION = "anthropic-version";
    static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String baseUrl;
    private final Supplier<String> bearerTokenSupplier;
    private final String subscriptionKey;

    public AiGatewayBackend(final String baseUrl, final Supplier<String> bearerTokenSupplier, final String subscriptionKey) {
        validateNullOrEmpty(baseUrl, "AI gateway base URL must be set.");
        validateNullOrEmpty(subscriptionKey, "AI gateway subscription key must be set.");
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.bearerTokenSupplier = Objects.requireNonNull(bearerTokenSupplier, "bearerTokenSupplier");
        this.subscriptionKey = subscriptionKey;
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
    public void close() {
        // Holds no resources: the token supplier and HTTP client are owned elsewhere.
    }
}
