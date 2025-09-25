package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.metadata.check.config.Config.getContainerName;
import static uk.gov.moj.cp.metadata.check.config.Config.getStorageConnectionString;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientFactory {
    private final BlobContainerClient containerClient;
    private static final String STORAGE_CONNECTION_STRING = getStorageConnectionString();

    public BlobClientFactory() {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                // TODO change to ManagedIdentity
                .connectionString(STORAGE_CONNECTION_STRING)
                .buildClient();

        this.containerClient = blobServiceClient.getBlobContainerClient(getContainerName());
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }
}