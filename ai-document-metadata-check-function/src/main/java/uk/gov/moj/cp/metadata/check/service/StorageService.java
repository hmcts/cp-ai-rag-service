package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;

import java.time.OffsetDateTime;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

public class StorageService {

    private static final String BLOB_CORE_URI_STR = "https://%s.blob.core.windows.net";
    public static final int EXPIRY_MINUTES = 120;
    private final String accountName;
    private final String containerName;

    public StorageService() {
        this.accountName = System.getenv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING);
        this.containerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME);
    }

    public String getSasUrl(final String documentName) {

        // 1. Initialize Service Client with Managed Identity
        final BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(String.format(BLOB_CORE_URI_STR, accountName))
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        // 2. Get User Delegation Key (Valid for the signing process)
        final OffsetDateTime start = OffsetDateTime.now().minusMinutes(1); // Buffer for clock skew
        final OffsetDateTime expiry = start.plusMinutes(EXPIRY_MINUTES);
        final UserDelegationKey key = blobServiceClient.getUserDelegationKey(start, expiry);

        // 3. Set Permissions (Create + Write for new file upload)
        final BlobSasPermission permissions = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);

        final BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiry, permissions)
                .setStartTime(start);

        // 4. Generate the URL for the specific blob
        final BlobClient blobClient = blobServiceClient
                .getBlobContainerClient(containerName)
                .getBlobClient(documentName);

        return blobClient.getBlobUrl() + "?" + blobClient.generateUserDelegationSas(sasValues, key);
    }
}