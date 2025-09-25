package uk.gov.moj.cp.metadata.check.util;

import static java.time.Instant.now;
import static java.util.UUID.fromString;
import static uk.gov.moj.cp.metadata.check.util.StringUtils.isNullOrBlank;

import uk.gov.moj.cp.ai.model.BlobMetadata;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobUtil {

    private static final Logger logger = LoggerFactory.getLogger(BlobUtil.class);
    private static final String BLOB_URL = "https://%s.blob.core.windows.net/%s/%s";
    private static final String DOCUMENT_ID = "document_id";

    /**
     * Validates that metadata contains required document_id.
     */
    public static boolean isValidMetadata(Map<String, String> metadata) {
        String documentId = metadata.get(DOCUMENT_ID);
        if (isNullOrBlank(documentId)) {
            logger.error("document_id is required but not found in metadata");
            return false;
        }
        try {
            fromString(documentId);
            return true;
        } catch (IllegalArgumentException e) {
            logger.error("Invalid document_id format: {}", documentId);
            return false;
        }
    }

    /**
     * Creates the queue message payload as a BlobMetadata record.
     */
    public static BlobMetadata createQueueMessage(String blobName, Map<String, String> blobMetadata,
                                                  String storageAccountName, String containerName) {
        String documentId = blobMetadata.get(DOCUMENT_ID);
        
        // Create additional metadata map (excluding document_id to avoid duplication)
        Map<String, String> additionalMetadata = new HashMap<>();
        for (Map.Entry<String, String> entry : blobMetadata.entrySet()) {
            if (!DOCUMENT_ID.equals(entry.getKey())) {
                additionalMetadata.put(entry.getKey(), entry.getValue());
            }
        }

        String blobUrl = String.format(BLOB_URL, storageAccountName, containerName, blobName);
        String currentTimestamp = now().toString();

        return new BlobMetadata(documentId, additionalMetadata, blobUrl, currentTimestamp);
    }

}
