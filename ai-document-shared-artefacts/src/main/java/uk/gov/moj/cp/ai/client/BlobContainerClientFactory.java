package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobContainerClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobContainerClientFactory.class);

    private static final ConcurrentHashMap<String, BlobContainerClient> BLOB_CONTAINER_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private BlobContainerClientFactory() {
    }

    public static BlobContainerClient getInstance(final String containerName) {

        final String endpoint = getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);

        final String containerCacheKey = endpoint + ":" + containerName;

        return BLOB_CONTAINER_CLIENT_CACHE.computeIfAbsent(
                containerCacheKey,
                key -> {
                    LOGGER.info("Creating new blob container client using managed identity for: {}", key);
                    return new BlobContainerClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .containerName(containerName)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                });
    }
}
