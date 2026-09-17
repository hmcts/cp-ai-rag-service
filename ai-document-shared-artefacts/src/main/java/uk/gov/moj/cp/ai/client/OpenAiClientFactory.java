package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.config.OpenAiClientConfiguration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAiClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiClientFactory.class);

    private static final String AZURE_COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default";

    private static final ConcurrentHashMap<String, OpenAIClient> OPENAI_CLIENT_CACHE = new ConcurrentHashMap<>();

    // Tokens are minted directly from the shared credential rather than via
    // AuthenticationUtil.getBearerTokenSupplier: the latter drives an internal azure-core
    // pipeline that consistently failed (ClosedChannelException) inside the Azure Functions
    // hosts on restricted-egress CI agents, while direct getTokenSync uses the identity
    // client's own transport and in-memory token cache - the same path the storage and
    // search clients already use in the same process (DD-43423).
    private static final Supplier<String> SHARED_BEARER_TOKEN_SUPPLIER =
            bearerTokenSupplier(getCredentialInstance());

    private OpenAiClientFactory() {
    }

    public static OpenAIClient getInstance(final String endpoint) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable must be set.");

        return OPENAI_CLIENT_CACHE.computeIfAbsent(
                endpoint,
                key -> {
                    LOGGER.info("Creating new OpenAI client for: {}", key);

                    // CRITICAL: The client is built here using the single, shared bearer token
                    // supplier sourced from the Azure default credential chain (Managed Identity
                    // in deployed environments, developer credentials locally).
                    final OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                            .baseUrl(baseUrlOf(key))
                            .credential(BearerTokenCredential.create(SHARED_BEARER_TOKEN_SUPPLIER));

                    // Applied inside computeIfAbsent so the configuration log is emitted exactly once per
                    // endpoint (on first build) rather than on every getInstance call.
                    return applyConfiguration(builder).build();
                }
        );
    }

    static Supplier<String> bearerTokenSupplier(final TokenCredential credential) {
        final TokenRequestContext tokenRequestContext = new TokenRequestContext().addScopes(AZURE_COGNITIVE_SCOPE);
        return () -> credential.getTokenSync(tokenRequestContext).getToken();
    }

    // Endpoint app settings are configured both with and without a trailing slash; without the
    // strip a trailing slash yields "…//openai/v1".
    static String baseUrlOf(final String endpoint) {
        return removeTrailingSlash(endpoint) + "/openai/v1";
    }

    static OpenAIOkHttpClient.Builder applyConfiguration(final OpenAIOkHttpClient.Builder builder) {

        final int maxRetries = OpenAiClientConfiguration.getMaxRetries();
        final Timeout timeout = OpenAiClientConfiguration.getTimeout();

        LOGGER.info("Configuring OpenAI client with maxRetries: {}, requestTimeoutSeconds: {} (also applied to the "
                        + "read phase), connectTimeoutSeconds: {}, writeTimeoutSeconds: {}",
                maxRetries,
                timeout.request().toSeconds(),
                timeout.connect().toSeconds(),
                timeout.write().toSeconds());

        return builder
                .maxRetries(maxRetries)
                .timeout(timeout);
    }
}
