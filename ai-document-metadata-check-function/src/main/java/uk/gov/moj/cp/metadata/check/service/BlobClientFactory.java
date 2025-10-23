package uk.gov.moj.cp.metadata.check.service;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobClientFactory {

    private final BlobContainerClient containerClient;

    // Managed identity constructor - currently in use
    public BlobClientFactory(String storageAccountName, String containerName) {
        if (isNullOrEmpty(storageAccountName) || isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Storage account name and container name cannot be null or empty");
        }

        String storageAccountUrl = format("https://%s.blob.core.windows.net", storageAccountName);
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