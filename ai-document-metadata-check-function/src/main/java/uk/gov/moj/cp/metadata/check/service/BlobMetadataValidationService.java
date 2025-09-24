package uk.gov.moj.cp.metadata.check.service;

import static java.lang.Boolean.TRUE;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts Metadata
 */
public class BlobMetadataValidationService {

    private static final Logger logger = LoggerFactory.getLogger(BlobClientService.class);
    BlobClientService blobClientService;

    public BlobMetadataValidationService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public Map<String, String> extractBlobMetadata(String blobName) {
        Map<String, String> metadata = new HashMap<>();

        try {
            BlobClient blobClient = blobClientService.getBlobClient(blobName);
            if (TRUE.equals(blobClient.exists())) {

                BlobProperties properties = blobClient.getProperties();
                Map<String, String> customMetadata = properties.getMetadata();
                if (customMetadata != null) {
                    metadata.putAll(customMetadata);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to extract metadata for blob: {}", blobName, e);
        }

        return metadata;
    }
}
