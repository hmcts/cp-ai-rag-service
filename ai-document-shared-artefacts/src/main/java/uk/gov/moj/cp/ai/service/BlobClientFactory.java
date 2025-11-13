package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.CredentialUtil.getDefaultAzureCredentialBuilder;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.nio.charset.StandardCharsets;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobClientFactory.class);

    private final BlobContainerClient containerClient;

    public BlobClientFactory(String endpoint, String containerName) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Storage account endpoint and container name cannot be null or empty");
        }

        LOGGER.info("Connecting to Blob Storage endpoint '{}' and container '{}'", endpoint, containerName);

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(getDefaultAzureCredentialBuilder())
                .buildClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);

        LOGGER.info("Initialized azure blob storage connectivity with Managed Identity.");
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }

    public void addBlob(final String documentName, final String payload) {
        containerClient.createIfNotExists();
        final byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        BlobClient blobClient = containerClient.getBlobClient(documentName);
        blobClient.upload(new java.io.ByteArrayInputStream(payloadBytes), payloadBytes.length, true);
    }
}