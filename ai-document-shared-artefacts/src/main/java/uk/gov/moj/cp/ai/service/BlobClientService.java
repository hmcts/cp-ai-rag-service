package uk.gov.moj.cp.ai.service;

import static com.azure.storage.common.sas.SasProtocol.HTTPS_ONLY;
import static java.lang.String.format;
import static java.util.Objects.isNull;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.BlobContainerClientFactory;
import uk.gov.moj.cp.ai.client.BlobServiceClientFactory;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
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

        final BlobSasPermission permissions = new BlobSasPermission().setCreatePermission(true);

        final BlobServiceSasSignatureValues sasValues = new BlobServiceSasSignatureValues(expiry, permissions)
                .setStartTime(start)
                .setProtocol(HTTPS_ONLY);

        final BlobClient blobClient = containerClient.getBlobClient(blobName);
        return format(SAS_URL_STR, blobClient.getBlobUrl(), blobClient.generateUserDelegationSas(sasValues, key));
    }

    public boolean isBlobAvailable(final String documentName) {

        final BlobClient blobClient = getBlobClient(documentName);
        final BlobProperties blobProperties = blobClient.getProperties();

        if (isNull(blobProperties)) {
            // Blob properties should never be null here, but just in case...
            throw new IllegalStateException("Blob properties for '" + documentName + "' could not be retrieved.");
        }

        if (CopyStatusType.PENDING == blobProperties.getCopyStatus()) {
            // Blob is still being copied and happens when using async copy operations
            throw new IllegalStateException("Blob '" + documentName + "' is still being copied.  Copy status is " + blobProperties.getCopyStatus());
        }

        //Blob was placed synchronously / atomic operation or  async copy operations has completed with status SUCCESS
        return isNull(blobProperties.getCopyStatus()) || CopyStatusType.SUCCESS == blobProperties.getCopyStatus();
    }
}