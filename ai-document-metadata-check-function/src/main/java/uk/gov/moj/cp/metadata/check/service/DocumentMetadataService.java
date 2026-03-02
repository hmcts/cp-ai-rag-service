package uk.gov.moj.cp.metadata.check.service;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.FunctionEnvironment;
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
        final FunctionEnvironment env = FunctionEnvironment.get();
        this.blobClientService = new BlobClientService(env.storageConfig().documentLandingContainer());
    }

    public DocumentMetadataService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public Map<String, String> processDocumentMetadata(String documentName) throws MetadataValidationException {
        BlobClient blobClient = blobClientService.getBlobClient(documentName);

        final BlobProperties blobProperties = blobClient.getProperties();
        final boolean blobAvailability = isBlobAvailable(documentName, blobProperties);


        if (!blobAvailability || Boolean.FALSE.equals(blobClient.exists())) {
            throw new MetadataValidationException("Blob not found: " + documentName);
        }

        final Map<String, String> metadataMap = new HashMap<>(blobProperties.getMetadata());
        return validateAndNormalizeMetadata(metadataMap, documentName);
    }

    private boolean isBlobAvailable(final String documentName, final BlobProperties blobProperties) {

        if (null == blobProperties) {
            // Blob properties should never be null here, but just in case...
            throw new IllegalStateException("Blob properties for '" + documentName + "' could not be retrieved.");
        }

        if (CopyStatusType.PENDING == blobProperties.getCopyStatus()) {
            // Blob is still being copied and happens when using async copy operations
            throw new IllegalStateException("Blob '" + documentName + "' is still being copied.  Copy status is " + blobProperties.getCopyStatus());
        }

        //Blob was placed synchronously / atomic operation or  async copy operations has completed with status SUCCESS
        return null == blobProperties.getCopyStatus() || CopyStatusType.SUCCESS == blobProperties.getCopyStatus();

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