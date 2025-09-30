package uk.gov.moj.cp.metadata.check.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientFactory {

    private final BlobContainerClient containerClient;

    public BlobClientFactory(final String storageConnectionString, final String containerName) {
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                // TODO change to ManagedIdentity
                .connectionString(storageConnectionString)
                .buildClient();

        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }
}