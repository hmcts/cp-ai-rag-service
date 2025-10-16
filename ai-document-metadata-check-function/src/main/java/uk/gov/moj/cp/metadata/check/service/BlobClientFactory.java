package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.util.StringUtil.*;

import uk.gov.moj.cp.ai.util.StringUtil;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientFactory {

    private final BlobContainerClient containerClient;

    public BlobClientFactory(String connectionString, String containerName) {
        if (isNullOrEmpty(connectionString) || isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Connection string and container name cannot be null or empty");
        }

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
    }

    // using managed identity remove the code for
    public BlobClientFactory(String storageAccountName, String containerName, boolean useManagedIdentity) {
        if (isNullOrEmpty(storageAccountName) || isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Storage account name and container name cannot be null or empty");
        }

        String storageAccountUrl = String.format("https://%s.blob.core.windows.net", storageAccountName);
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(storageAccountUrl)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
    }

    public BlobClient getBlobClient(final String documentName) {
        return containerClient.getBlobClient(documentName);
    }
}