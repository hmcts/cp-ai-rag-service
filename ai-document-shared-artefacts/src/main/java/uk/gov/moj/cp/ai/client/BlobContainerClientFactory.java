package uk.gov.moj.cp.ai.client;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.createNettyClient;
import static uk.gov.moj.cp.ai.client.config.ClientConfiguration.getRetryOptions;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import java.util.concurrent.ConcurrentHashMap;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobContainerClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobContainerClientFactory.class);

    private static final ConcurrentHashMap<String, BlobContainerClient> BLOB_CONTAINER_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private BlobContainerClientFactory() {
    }

    public static BlobContainerClient getInstance(final String containerName) {

        final ConnectionMode connectionMode = ConnectionMode.valueOf(getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, ConnectionMode.MANAGED_IDENTITY.name()));

        return switch (connectionMode) {
            case CONNECTION_STRING -> {
                final String connectionString = getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING);
                yield getConnectionStringBlobContainerClient(connectionString, containerName);
            }
            case MANAGED_IDENTITY -> {
                final String endpoint = getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);
                yield getManagedIdentityBlobContainerClient(endpoint, containerName);
            }
            default ->
                    throw new IllegalArgumentException("Unsupported connection mode for Blob Container Client: " + connectionMode);
        };


    }

    private static BlobContainerClient getManagedIdentityBlobContainerClient(final String endpoint, final String containerName) {


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

    private static BlobContainerClient getConnectionStringBlobContainerClient(final String connectionString, final String containerName) {


        final String containerCacheKey = connectionString + ":" + containerName;

        return BLOB_CONTAINER_CLIENT_CACHE.computeIfAbsent(
                containerCacheKey,
                key -> {
                    LOGGER.info("Creating new blob container client using connection string for: {}", key);
                    return new BlobContainerClientBuilder()
                            .connectionString(connectionString)
                            .containerName(containerName)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                });
    }
}
