package uk.gov.moj.cp.metadata.check.util;

import static java.util.UUID.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobUtil {

    private static final Logger logger = LoggerFactory.getLogger(BlobUtil.class);

    /**
     * Validates that metadata contains required document_id.
     */
    public static boolean isValidMetadata(Map<String, String> metadata) {
        String documentId = metadata.get("document_id");
        if (documentId == null || documentId.trim().isEmpty()) {
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
     * Creates the queue message payload as a flat Map<String, Object>.
     */
    public static Map<String, Object> createQueueMessage(String blobName, Map<String, String> blobMetadata,
                                                         String storageAccountName, String containerName) {
        Map<String, Object> queueMessage = new HashMap<>();

        // Add document_id
        queueMessage.put("document_id", blobMetadata.get("document_id"));

        // Add all metadata key-value pairs
        for (Map.Entry<String, String> entry : blobMetadata.entrySet()) {
            if (!"document_id".equals(entry.getKey())) { // Avoid duplicate
                queueMessage.put(entry.getKey(), entry.getValue());
            }
        }

        // Add blob_url
        String blobUrl = String.format("https://%s.blob.core.windows.net/%s/%s",
                storageAccountName, containerName, blobName);
        queueMessage.put("blob_url", blobUrl);

        // Add current_timestamp (UTC)
        queueMessage.put("current_timestamp", Instant.now().toString());

        return queueMessage;
    }

}
