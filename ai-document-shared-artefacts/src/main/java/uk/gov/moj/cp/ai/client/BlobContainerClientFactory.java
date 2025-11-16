package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobContainerClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobContainerClientFactory.class);

    private static final ConcurrentHashMap<String, BlobContainerClient> BLOB_CONTAINER_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, BlobServiceClient> BLOB_SERVICE_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private BlobContainerClientFactory() {
    }

    public static BlobContainerClient getInstance(final String endpoint, final String containerName) {

        if (isNullOrEmpty(endpoint)) {
            throw new IllegalArgumentException("Endpoint environment variable for Blob Service must be set.");
        }

        if (isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Container name cannot be null or empty.");
        }

        final String containerCacheKey = endpoint + ":" + containerName;

        final BlobServiceClient blobServiceClient = BLOB_SERVICE_CLIENT_CACHE.computeIfAbsent(
                endpoint,
                key -> {
                    LOGGER.info("Creating new blob service client for: {}", key);

                    // CRITICAL: The client is built here using the single, shared credential.
                    return new BlobServiceClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .buildClient();
                });

        return BLOB_CONTAINER_CLIENT_CACHE.computeIfAbsent(
                containerCacheKey,
                key -> {
                    LOGGER.info("Creating new blob container client for: {}", key);
                    return blobServiceClient.getBlobContainerClient(containerName);
                });

    }
}
