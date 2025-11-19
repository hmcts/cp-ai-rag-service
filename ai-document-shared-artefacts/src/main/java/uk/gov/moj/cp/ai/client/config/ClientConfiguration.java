package uk.gov.moj.cp.ai.client.config;

import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import java.time.Duration;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfiguration.class);

    private static final String DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS = "180";
    private static final String DEFAULT_CONNECT_TIMEOUT_IN_SECONDS = "10";
    private static final String DEFAULT_READ_TIMEOUT_IN_SECONDS = "60";

    private static final String DEFAULT_MAX_RETRIES = "3";
    private static final String DEFAULT_BASE_DELAY_IN_SECONDS = "1";
    private static final String DEFAULT_MAX_DELAY_IN_SECONDS = "60";

    private ClientConfiguration() {
    }

    public static RetryOptions getRetryOptions() {
        final int maxRetries = getRequiredEnvAsInteger("AZURE_CLIENT_MAX_RETRIES", DEFAULT_MAX_RETRIES);
        final int baseDelayInSeconds = getRequiredEnvAsInteger("AZURE_CLIENT_BASE_DELAY_IN_SECONDS", DEFAULT_BASE_DELAY_IN_SECONDS);
        final int maxDelayInSeconds = getRequiredEnvAsInteger("AZURE_CLIENT_MAX_DELAY_IN_SECONDS", DEFAULT_MAX_DELAY_IN_SECONDS);

        LOGGER.info("Initiating exponential client retry options with maxRetries: {}, baseDelayInSeconds: {}, maxDelayInSeconds: {}",
                maxRetries, baseDelayInSeconds, maxDelayInSeconds);

        final ExponentialBackoffOptions exponentialBackoffOptions = new ExponentialBackoffOptions();
        exponentialBackoffOptions.setMaxRetries(maxRetries);
        exponentialBackoffOptions.setBaseDelay(Duration.ofSeconds(baseDelayInSeconds));
        exponentialBackoffOptions.setMaxDelay(Duration.ofSeconds(maxDelayInSeconds));
        return new RetryOptions(exponentialBackoffOptions);
    }

    public static HttpClient createNettyClient() {

        final int responseTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS", DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS);
        final int connectTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS", DEFAULT_CONNECT_TIMEOUT_IN_SECONDS);
        final int readTimeoutInSeconds = getRequiredEnvAsInteger("HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS", DEFAULT_READ_TIMEOUT_IN_SECONDS);

        LOGGER.info("Creating Netty HTTP client with responseTimeoutInSeconds: {}, connectTimeoutInSeconds: {}, readTimeoutInSeconds: {}",
                responseTimeoutInSeconds, connectTimeoutInSeconds, readTimeoutInSeconds);

        // Configure the Netty HTTP client with custom timeouts.  Azure SDK uses this as default
        return new NettyAsyncHttpClientBuilder()
                .responseTimeout(Duration.ofSeconds(responseTimeoutInSeconds))
                .connectTimeout(Duration.ofSeconds(connectTimeoutInSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutInSeconds))
                .build();
    }
}