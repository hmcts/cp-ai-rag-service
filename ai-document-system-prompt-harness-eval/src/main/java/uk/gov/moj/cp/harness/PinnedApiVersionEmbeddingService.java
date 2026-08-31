package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import uk.gov.moj.cp.ai.service.EmbeddingService;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.OpenAIServiceVersion;

/**
 * {@link EmbeddingService} pinned to a GA api-version, because the shared
 * {@code AzureOpenAiClientFactory} leaves the SDK on its default service version — the latest
 * preview ({@code 2025-01-01-preview} on this SDK) — which the platform's hardened OpenAI
 * resources reject with a misleading 401 {@code PermissionDenied} ("Principal does not have
 * access to API/Operation"): the restriction is on the preview API surface, not the caller's
 * RBAC. GA api-versions remain allowed, and the embedding vectors are identical either way
 * (the api-version is transport, the deployment defines the model).
 *
 * <p>Harness-local by design, same containment rule as {@link AnthropicChatService}: the shared
 * factory serves the deployed functions and is not changed for an offline tool. If the deployed
 * functions hit the same 401 against hardened resources, fixing the shared factory's service
 * version is a separate, production-tested change.
 */
public final class PinnedApiVersionEmbeddingService extends EmbeddingService {

    /** Newest GA version this SDK offers; verified allowed on the hardened STE resource. */
    private static final OpenAIServiceVersion PINNED_VERSION = OpenAIServiceVersion.V2024_06_01;

    public PinnedApiVersionEmbeddingService(final String endpoint, final String deploymentName) {
        super(buildPinnedClient(endpoint), deploymentName);
    }

    private static OpenAIClient buildPinnedClient(final String endpoint) {
        // Mirrors AzureOpenAiClientFactory (credential, retry, netty client) with only the
        // service version pinned. No cache needed: the harness builds one embedding client.
        return new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(getCredentialInstance())
                .retryOptions(getRetryOptions())
                .httpClient(createNettyClient())
                .serviceVersion(PINNED_VERSION)
                .buildClient();
    }
}
