package uk.gov.moj.cp.metadata.check.service;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for extracting and validating blob metadata.
 */
public class DocumentMetadataService {

    private static final String DOCUMENT_ID = "document_id";
    private static final String METADATA = "metadata";
    private final BlobClientFactory blobClientFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentMetadataService() {
        String storageConnectionString = System.getenv("AI_RAG_SERVICE_STORAGE_ACCOUNT");
        String documentContainerName = System.getenv("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME");

        this.blobClientFactory = new BlobClientFactory(storageConnectionString, documentContainerName);
    }

    public DocumentMetadataService(final BlobClientFactory blobClientFactory) {
        this.blobClientFactory = blobClientFactory;
    }

    public Map<String, String> processDocumentMetadata(String documentName) {
        BlobClient blobClient = blobClientFactory.getBlobClient(documentName);

        if (!blobClient.exists()) {
            throw new MetadataValidationException("Blob not found: " + documentName);
        }

        Map<String, String> metadataMap = new HashMap<>(blobClient.getProperties().getMetadata());
        return validateAndNormalizeMetadata(metadataMap, documentName);
    }

    private Map<String, String> validateAndNormalizeMetadata(Map<String, String> metadataMap, String documentName) {
        try {
            String documentId = metadataMap.get(DOCUMENT_ID);
            if (isNullOrEmpty(documentId)) {
                throw new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName);
            }

            fromString(documentId);

            if (metadataMap.containsKey(METADATA)) {
                String metadataJson = metadataMap.get(METADATA);

                Map<String, String> nestedMetadata = objectMapper.readValue(metadataJson, Map.class);
                for (Map.Entry<String, String> entry : nestedMetadata.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
                        String reason = "Invalid nested metadata key/value: '" + key + "' in blob " + documentName;
                        throw new MetadataValidationException("Invalid metadata: " + reason);
                    }
                    metadataMap.put(key, value);
                }
                metadataMap.remove(METADATA);
            }
            return metadataMap;
        } catch (Exception e) {
            throw new MetadataValidationException("Invalid metadata for " + documentName + ": " + e.getMessage(), e);
        }
    }
}