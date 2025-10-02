package uk.gov.moj.cp.metadata.check.service;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.metadata.check.util.DocumentStatus.BLOB_NOT_FOUND;
import static uk.gov.moj.cp.metadata.check.util.DocumentStatus.INVALID_METADATA;
import static uk.gov.moj.cp.metadata.check.util.StringUtils.isNullOrBlank;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Service for extracting and validating blob metadata.
 */
public class DocumentMetadataService {

    private static final String DOCUMENT_ID = "document_id";
    private final BlobClientFactory blobClientFactory;
    private final TableStorageService tableStorageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentMetadataService(final String storageConnectionString,
                                   final String documentContainerName,
                                   final String documentIngestionOutcomeTable) {
        this.blobClientFactory = new BlobClientFactory(storageConnectionString, documentContainerName);
        this.tableStorageService = new TableStorageService(storageConnectionString, documentIngestionOutcomeTable);
    }

    public DocumentMetadataService(final BlobClientFactory blobClientFactory,
                                   final TableStorageService tableStorageService) {
        this.blobClientFactory = blobClientFactory;
        this.tableStorageService = tableStorageService;
    }

    public Map<String, String> processDocumentMetadata(String documentName) {
        BlobClient blobClient = blobClientFactory.getBlobClient(documentName);

        if (!blobClient.exists()) {
            recordFailure(documentName, BLOB_NOT_FOUND.name(), BLOB_NOT_FOUND.getReason());
            throw new MetadataValidationException("Blob not found: " + documentName);
        }

        Map<String, String> metadata = new HashMap<>(blobClient.getProperties().getMetadata());
        return validateAndNormalizeMetadata(metadata, documentName);
    }

    private Map<String, String> validateAndNormalizeMetadata(Map<String, String> metadata, String documentName) {
        try {
            String documentId = metadata.get(DOCUMENT_ID);
            if (isNullOrBlank(documentId)) {
                throw new MetadataValidationException("Invalid metadata: Missing document ID: " + documentName);
            }

            fromString(documentId);

            if (metadata.containsKey("metadata")) {
                String metadataJson = metadata.get("metadata");

                Map<String, String> nestedMetadata = objectMapper.readValue(metadataJson, Map.class);
                for (Map.Entry<String, String> entry : nestedMetadata.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (isNullOrBlank(key) || isNullOrBlank(value)) {
                        String reason = "Invalid nested metadata key/value: '" + key + "' in blob " + documentName;
                        throw new MetadataValidationException("Invalid metadata: " + reason);
                    }
                    metadata.put(key, value);
                }
                metadata.remove("metadata");
            }
            return metadata;
        } catch (Exception e) {
            recordFailure(documentName, INVALID_METADATA.name(), INVALID_METADATA.getReason());
            throw new MetadataValidationException("Invalid metadata for " + documentName + ": " + e.getMessage(), e);
        }

    }

    private void recordFailure(String documentName, String status, String reason) {
        DocumentIngestionOutcome documentIngestionOutcome = new DocumentIngestionOutcome();
        documentIngestionOutcome.setDocumentName(documentName);
        documentIngestionOutcome.setStatus(status);
        documentIngestionOutcome.setReason(reason);
        documentIngestionOutcome.setTimestamp(Instant.now().toString());

        tableStorageService.recordOutcome(documentIngestionOutcome);
    }
}