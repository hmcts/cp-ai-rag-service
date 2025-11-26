package uk.gov.moj.cp.metadata.check.service;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for extracting and validating blob metadata.
 */
public class DocumentMetadataService {

    private static final String DOCUMENT_ID = "document_id";
    private static final String METADATA = "metadata";
    private final BlobClientService blobClientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentMetadataService() {
        String endpoint = System.getenv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);
        String documentContainerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME);

        this.blobClientService = new BlobClientService(endpoint, documentContainerName);
    }

    public DocumentMetadataService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public Map<String, String> processDocumentMetadata(String documentName) throws MetadataValidationException {
        BlobClient blobClient = blobClientService.getBlobClient(documentName);

        final BlobProperties blobProperties = blobClient.getProperties();
        if (null == blobProperties || CopyStatusType.SUCCESS != blobProperties.getCopyStatus()) {
            throw new IllegalStateException("Blob '{}' is still being copied.  Copy status is " + blobProperties.getCopyStatus());
        }

        if (!blobClient.exists()) {
            throw new MetadataValidationException("Blob not found: " + documentName);
        }

        final Map<String, String> metadataMap = new HashMap<>(blobProperties.getMetadata());
        return validateAndNormalizeMetadata(metadataMap, documentName);
    }

    private Map<String, String> validateAndNormalizeMetadata(final Map<String, String> blobMetadata, final String documentName) throws MetadataValidationException {
        String documentId = blobMetadata.get(DOCUMENT_ID);
        if (isNullOrEmpty(documentId)) {
            throw new MetadataValidationException("Invalid metadata: Document ID missing for document '" + documentName + "'");
        }

        try {
            fromString(documentId);
        } catch (IllegalArgumentException ex) {
            throw new MetadataValidationException("Invalid metadata: Document ID '" + documentId + "' is not a valid UUID for document '" + documentName + "'");
        }

        if (blobMetadata.containsKey(METADATA)) {
            String metadataJson = blobMetadata.get(METADATA);

            Map<String, String> nestedMetadata = null;
            try {
                nestedMetadata = objectMapper.readValue(metadataJson, Map.class);
            } catch (final JsonProcessingException e) {
                throw new MetadataValidationException("Invalid metadata: Metadata attribute incorrectly supplied for document '" + documentName + "'");
            }

            for (Map.Entry<String, String> entry : nestedMetadata.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
                    String reason = "Invalid nested metadata key/value: '" + key + "' in blob " + documentName;
                    throw new MetadataValidationException("Invalid metadata: " + reason);
                }
                blobMetadata.put(key, value);
            }
            blobMetadata.remove(METADATA);
        }
        return blobMetadata;
    }
}