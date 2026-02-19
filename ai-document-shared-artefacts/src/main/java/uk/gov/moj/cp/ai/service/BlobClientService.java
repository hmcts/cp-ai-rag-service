package uk.gov.moj.cp.ai.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.BlobContainerClientFactory;
import uk.gov.moj.cp.ai.client.BlobServiceClientFactory;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobClientService {
    public static final int EXPIRY_MINUTES = 120;
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobClientService.class);

    private final BlobContainerClient containerClient;
    private final BlobServiceClient serviceClient;

    public BlobClientService(String containerName) {

        if (isNullOrEmpty(containerName)) {
            throw new IllegalArgumentException("Container name cannot be null or empty.");
        }

        this.containerClient = BlobContainerClientFactory.getInstance(containerName);
        this.serviceClient = BlobServiceClientFactory.getInstance(containerName);
    }

    protected BlobClientService(final BlobContainerClient containerClient,
                                final BlobServiceClient serviceClient) {
        this.containerClient = containerClient;
        this.serviceClient = serviceClient;
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

    public String getSasUrl(final String blobName) {
        // 1. Get User Delegation Key (Valid for the signing process)
        final OffsetDateTime start = OffsetDateTime.now().minusMinutes(1); // Buffer for clock skew
        final OffsetDateTime expiry = start.plusMinutes(EXPIRY_MINUTES);
        final UserDelegationKey key = serviceClient.getUserDelegationKey(start, expiry);

        // 2. Set Permissions (Create + Write for new file upload)
        final BlobSasPermission permissions = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);

        final BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiry, permissions)
                .setStartTime(start);

        // 3. Generate the URL for the specific blob
        final BlobClient blobClient = containerClient.getBlobClient(blobName);

        return blobClient.getBlobUrl() + "?" + blobClient.generateUserDelegationSas(sasValues, key);
    }
}