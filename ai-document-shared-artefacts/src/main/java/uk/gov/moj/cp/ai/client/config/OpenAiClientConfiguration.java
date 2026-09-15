package uk.gov.moj.cp.ai.client.config;

import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import java.time.Duration;

import com.openai.core.Timeout;

/**
 * Retry and timeout configuration for the official OpenAI Java SDK ({@code com.openai:openai-java}) client
 * built by {@link uk.gov.moj.cp.ai.client.OpenAiClientFactory}.
 *
 * <p>Sibling of {@link ClientConfiguration}, which configures the Azure SDK clients (AI Search, Blob, Table,
 * Document Intelligence, Azure OpenAI). The same environment variables and the same defaults are deliberately
 * reused here so both SDK paths behave alike while the provider toggle exists:
 *
 * <ul>
 *   <li>{@code AZURE_CLIENT_MAX_RETRIES} (default 3) &rarr; {@code OpenAIOkHttpClient.Builder.maxRetries}</li>
 *   <li>{@code HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS} (default 180) &rarr; {@link Timeout#request()}
 *       <em>and</em> {@link Timeout#read()} (see below)</li>
 *   <li>{@code HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS} (default 10) &rarr; {@link Timeout#connect()}</li>
 *   <li>{@code HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS} (default 60) &rarr; {@link Timeout#write()}</li>
 * </ul>
 *
 * <p><strong>The read phase deliberately takes the response-timeout value, and
 * {@code HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS} is not applied on this client.</strong> {@link Timeout#read()} maps
 * onto OkHttp's read timeout, which — unlike Netty's — bounds the wait for the <em>first</em> response byte as well
 * as any subsequent inter-packet gap. Calls through this client are non-streaming Responses-API calls, so the whole
 * of the model's processing time falls inside that first read. Feeding the read phase the (much smaller) dedicated
 * read-timeout value would therefore cap model latency well below the Azure/Netty leg, where
 * {@code responseTimeout} bounds the response wait and {@code readTimeout} only applies mid-body. Giving the read
 * phase the response-timeout value keeps the two provider legs at parity while the toggle exists.
 * {@code HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS} remains fully in force for the Azure SDK clients via
 * {@link ClientConfiguration#createNettyClient()}. The write phase keeps its own variable: request bodies are
 * small, so it is a genuine inter-packet bound there.
 *
 * <p><strong>The retry backoff curve is not configurable on this client.</strong> The OpenAI SDK applies a fixed
 * exponential backoff of roughly 0.5 seconds up to 8 seconds with jitter. A server-supplied
 * {@code X-Should-Retry} header controls <em>whether</em> a retry happens at all, while {@code Retry-After} and
 * {@code Retry-After-Ms} shape the delay before it. Only the retry <em>count</em> can be set.
 *
 * <p>Consequently {@code AZURE_CLIENT_BASE_DELAY_IN_SECONDS} and {@code AZURE_CLIENT_MAX_DELAY_IN_SECONDS} have
 * <strong>no effect on this client</strong> and are never read here. They remain fully in force for the Azure SDK
 * clients via {@link ClientConfiguration#getRetryOptions()}, so setting them is safe and changes nothing on the
 * OpenAI path.
 */
public class OpenAiClientConfiguration {

    private static final String DEFAULT_MAX_RETRIES = "3";

    private static final String DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS = "180";
    private static final String DEFAULT_CONNECT_TIMEOUT_IN_SECONDS = "10";
    private static final String DEFAULT_WRITE_TIMEOUT_IN_SECONDS = "60";

    private OpenAiClientConfiguration() {
    }

    public static int getMaxRetries() {
        return getRequiredEnvAsInteger("AZURE_CLIENT_MAX_RETRIES", DEFAULT_MAX_RETRIES);
    }

    public static Timeout getTimeout() {

        final int responseTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS", DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS);
        final int connectTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS", DEFAULT_CONNECT_TIMEOUT_IN_SECONDS);
        final int writeTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS", DEFAULT_WRITE_TIMEOUT_IN_SECONDS);

        return Timeout.builder()
                .request(Duration.ofSeconds(responseTimeoutInSeconds))
                .connect(Duration.ofSeconds(connectTimeoutInSeconds))
                // OkHttp's read timeout also bounds the wait for the first response byte, i.e. the model's
                // processing time on these non-streaming calls — so it takes the response-timeout value, not
                // HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS. See the class Javadoc.
                .read(Duration.ofSeconds(responseTimeoutInSeconds))
                .write(Duration.ofSeconds(writeTimeoutInSeconds))
                .build();
    }
}
