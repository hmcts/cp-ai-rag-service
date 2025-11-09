package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientFactory {

    private final BlobContainerClient containerClient;

    public BlobClientFactory(String endpoint, String containerName) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Storage account endpoint and container name cannot be null or empty");
        }

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }
}