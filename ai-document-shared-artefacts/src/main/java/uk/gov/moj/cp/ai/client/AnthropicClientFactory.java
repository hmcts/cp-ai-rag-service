package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_SUBSCRIPTION_KEY;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_GATEWAY_TOKEN_SCOPE;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.client.config.OpenAiClientConfiguration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.AzureCliCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and caches Anthropic SDK clients for Claude served through the AI gateway.
 *
 * <p>Authentication uses the shared {@code DefaultAzureCredential}: the function app's managed
 * identity in Azure, developer credentials locally. The token is requested for the gateway's own
 * app registration ({@code AI_GATEWAY_TOKEN_SCOPE}, e.g. {@code api://<client-id>/.default}) and sent
 * with the gateway subscription key ({@code AI_GATEWAY_SUBSCRIPTION_KEY}). Tokens are fetched with
 * {@code getTokenSync} (not {@code AuthenticationUtil.getBearerTokenSupplier}, which failed inside the
 * Functions hosts on restricted-egress agents, DD-43423) and reused until shortly before expiry.
 * For local development where the gateway role sits on a different account, see
 * {@link #gatewayCredential}.
 *
 * <p>Retries and timeouts come from the same {@code AZURE_CLIENT_MAX_RETRIES} /
 * {@code HTTP_CLIENT_*} variables as the OpenAI client, mapped the same way (see
 * {@link OpenAiClientConfiguration}).
 */
public final class AnthropicClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicClientFactory.class);

    private static final ConcurrentHashMap<String, AnthropicClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    /** Set by the Azure App Service platform on every function app, never on a developer machine. */
    static final String AZURE_HOSTED_INDICATOR = "WEBSITE_INSTANCE_ID";

    private AnthropicClientFactory() {
    }

    /**
     * @param gatewayBaseUrl the gateway's Messages API base URL, without the trailing
     *                       {@code /v1/messages} (the SDK appends it)
     */
    public static AnthropicClient getAiGatewayInstance(final String gatewayBaseUrl) {
        validateNullOrEmpty(gatewayBaseUrl, "AI gateway base URL must be set.");
        return CLIENT_CACHE.computeIfAbsent(removeTrailingSlash(gatewayBaseUrl), key -> {
            final String scope = getRequiredEnv(AI_GATEWAY_TOKEN_SCOPE);
            final String subscriptionKey = getRequiredEnv(AI_GATEWAY_SUBSCRIPTION_KEY);
            LOGGER.info("Creating Anthropic client for the AI gateway at {} (token scope {})", key, scope);
            final TokenCredential credential = gatewayCredential(System::getenv, getCredentialInstance());
            return buildAiGatewayClient(key, new CachingTokenSupplier(credential, scope), subscriptionKey);
        });
    }

    /**
     * The credential for gateway tokens.
     *
     * <p>Normally the shared credential: the managed identity in Azure, the default Azure CLI account
     * locally. Locally the account with the gateway role can differ from the one used for the other
     * Azure services (a different user in a different tenant). Setting
     * {@code AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION} to a subscription of that account makes the Azure CLI
     * use it for gateway tokens only: the CLI ties each subscription to the account that signed in to
     * it, whereas choosing by tenant keeps the default account and fails when that account is not in
     * the tenant.
     *
     * @throws IllegalStateException when the local setting is present in an Azure-hosted app
     */
    static TokenCredential gatewayCredential(final UnaryOperator<String> env, final TokenCredential sharedCredential) {
        final String subscription = env.apply(AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION);
        if (isNullOrEmpty(subscription) || subscription.isBlank()) {
            return sharedCredential;
        }
        if (!isNullOrEmpty(env.apply(AZURE_HOSTED_INDICATOR))) {
            throw new IllegalStateException(AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION
                    + " is a local-development setting and must not be set in Azure; remove it from the app settings"
                    + " so the managed identity is used.");
        }
        LOGGER.info("AI gateway tokens come from the Azure CLI account of subscription {} ({} is set; local development)",
                subscription.trim(), AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION);
        return new AzureCliCredentialBuilder().subscription(subscription.trim()).build();
    }

    static AnthropicClient buildAiGatewayClient(final String gatewayBaseUrl, final Supplier<String> bearerTokenSupplier,
                                                final String subscriptionKey) {
        final int maxRetries = OpenAiClientConfiguration.getMaxRetries();
        final com.openai.core.Timeout configured = OpenAiClientConfiguration.getTimeout();
        final Timeout timeout = Timeout.builder()
                .request(configured.request())
                .connect(configured.connect())
                .read(configured.read())
                .write(configured.write())
                .build();
        LOGGER.info("Configuring Anthropic client with maxRetries: {}, requestTimeoutSeconds: {}, connectTimeoutSeconds: {}, "
                        + "writeTimeoutSeconds: {}",
                maxRetries, timeout.request().toSeconds(), timeout.connect().toSeconds(), timeout.write().toSeconds());

        return AnthropicOkHttpClient.builder()
                .backend(new AiGatewayBackend(gatewayBaseUrl, bearerTokenSupplier, subscriptionKey))
                .maxRetries(maxRetries)
                .timeout(timeout)
                .build();
    }
}
