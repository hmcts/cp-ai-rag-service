package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.azure.identity.AuthenticationUtil;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.credential.BearerTokenCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAiClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiClientFactory.class);

    private static final String AZURE_COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default";

    private static final ConcurrentHashMap<String, OpenAIClient> OPENAI_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final Supplier<String> SHARED_BEARER_TOKEN_SUPPLIER = AuthenticationUtil.getBearerTokenSupplier(
            getCredentialInstance(),
            AZURE_COGNITIVE_SCOPE);

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
                    return OpenAIOkHttpClient.builder()
                            .baseUrl(key + "/openai/v1")
                            .credential(BearerTokenCredential.create(SHARED_BEARER_TOKEN_SUPPLIER))
                            .build();
                }
        );
    }
}
