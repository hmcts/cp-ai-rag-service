package uk.gov.moj.cp.ai.service;

import static java.lang.String.format;
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
    private static final String SAS_URL_STR = "%s?%s";
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

    public String getSasUrl(final String blobName, int urlExpiryMinutes) {
        final OffsetDateTime start = OffsetDateTime.now().minusMinutes(1); // Buffer for clock skew
        final OffsetDateTime expiry = start.plusMinutes(urlExpiryMinutes);
        final UserDelegationKey key = serviceClient.getUserDelegationKey(start, expiry);

        final BlobSasPermission permissions = new BlobSasPermission()
                .setCreatePermission(true)
                .setWritePermission(true);

        final BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiry, permissions)
                .setStartTime(start);

        final BlobClient blobClient = containerClient.getBlobClient(blobName);
        return format(SAS_URL_STR, blobClient.getBlobUrl(), blobClient.generateUserDelegationSas(sasValues, key));
    }
}