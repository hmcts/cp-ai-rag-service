package uk.gov.moj.cp.metadata.check.service;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts Metadata
 */
public class BlobMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(BlobMetadataService.class);
    BlobClientService blobClientService;

    public BlobMetadataService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public Map<String, String> extractBlobMetadata(String documentName) {
        Map<String, String> metadata = new HashMap<>();

        try {
            BlobClient blobClient = blobClientService.getBlobClient(documentName);

            if (blobClient.exists()) {
                BlobProperties properties = blobClient.getProperties();
                Map<String, String> customMetadata = properties.getMetadata();
                if (customMetadata != null) {
                    metadata.putAll(customMetadata);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to extract metadata for blob: {}", documentName, e);
        }

        return metadata;
    }
}
