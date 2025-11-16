package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.BlobContainerClientFactory;

import java.nio.charset.StandardCharsets;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobClientService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobClientService.class);

    private final BlobContainerClient containerClient;

    public BlobClientService(String endpoint, String containerName) {

        if (isNullOrEmpty(endpoint)) {
            throw new IllegalArgumentException("Endpoint environment variable for Blob Service must be set.");
        }

        if (isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Container name cannot be null or empty.");
        }

        this.containerClient = BlobContainerClientFactory.getInstance(endpoint, containerName);

    }

    protected BlobClientService(BlobContainerClient containerClient) {
        this.containerClient = containerClient;
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }

    public void addBlob(final String documentName, final String payload) {
        final byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        BlobClient blobClient = containerClient.getBlobClient(documentName);
        blobClient.upload(new java.io.ByteArrayInputStream(payloadBytes), payloadBytes.length, true);
        LOGGER.info("Blob added: {}/{}", containerClient.getBlobContainerName(), documentName);
    }
}