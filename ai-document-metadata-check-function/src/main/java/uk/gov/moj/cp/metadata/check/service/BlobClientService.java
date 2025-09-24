package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.metadata.check.config.Config.getContainerName;
import static uk.gov.moj.cp.metadata.check.config.Config.getStorageConnectionString;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientService {
    public BlobClient getBlobClient(final String documentName) {

        String connectionString = getStorageConnectionString();
        if (connectionString == null) {
            throw new IllegalStateException("AzureWebJobsStorage env var not set");
        }
        String containerName = getContainerName();


        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                // TODO change to ManagedIdentity
                .connectionString(connectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);

        return containerClient.getBlobClient(documentName);

    }
}