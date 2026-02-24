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
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobServiceClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobServiceClientFactory.class);

    private static final ConcurrentHashMap<String, BlobServiceClient> BLOB_SERVICE_CLIENT_CACHE = new ConcurrentHashMap<>();
    private static final TokenCredential SHARED_CREDENTIAL = getCredentialInstance();

    private BlobServiceClientFactory() {
    }

    public static BlobServiceClient getInstance(final String containerName) {

        final ConnectionMode connectionMode = ConnectionMode.valueOf(getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, ConnectionMode.MANAGED_IDENTITY.name()));

        return switch (connectionMode) {
            case CONNECTION_STRING -> {
                final String connectionString = getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING);
                yield getConnectionStringBlobServiceClient(connectionString, containerName);
            }
            case MANAGED_IDENTITY -> {
                final String endpoint = getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);
                yield getManagedIdentityBlobServiceClient(endpoint, containerName);
            }
            default ->
                    throw new IllegalArgumentException("Unsupported connection mode for Blob Container Client: " + connectionMode);
        };
    }

    private static BlobServiceClient getManagedIdentityBlobServiceClient(final String endpoint, final String containerName) {

        final String containerCacheKey = endpoint + ":" + containerName;

        return BLOB_SERVICE_CLIENT_CACHE.computeIfAbsent(
                containerCacheKey,
                key -> {
                    LOGGER.info("Creating new blob container client using managed identity for: {}", key);
                    return new BlobServiceClientBuilder()
                            .endpoint(endpoint)
                            .credential(SHARED_CREDENTIAL)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                });
    }

    private static BlobServiceClient getConnectionStringBlobServiceClient(final String connectionString, final String containerName) {

        final String containerCacheKey = connectionString + ":" + containerName;

        return BLOB_SERVICE_CLIENT_CACHE.computeIfAbsent(
                containerCacheKey,
                key -> {
                    LOGGER.info("Creating new blob container client using connection string for: {}", key);
                    return new BlobServiceClientBuilder()
                            .connectionString(connectionString)
                            .retryOptions(getRetryOptions())
                            .httpClient(createNettyClient())
                            .buildClient();
                });
    }
}
