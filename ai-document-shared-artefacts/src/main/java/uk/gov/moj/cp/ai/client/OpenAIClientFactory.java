package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.TokenCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenAIClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAIClientFactory.class);

    private static final ConcurrentHashMap<String, OpenAIClient> OPENAI_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private OpenAIClientFactory() {
    }

    public static OpenAIClient getInstance(final String endpoint) {

        validateNullOrEmpty(endpoint, "Endpoint environment variable must be set.");

        return OPENAI_CLIENT_CACHE.computeIfAbsent(
                endpoint,
                key -> {
                    LOGGER.info("Creating new Open AI client for: {}", key);

                    // CRITICAL: The client is built here using the single, shared credential.
                    return new OpenAIClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .buildClient();
                }
        );

    }
}
