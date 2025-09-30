package uk.gov.moj.cp.metadata.check.service;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.metadata.check.util.BlobStatus.BLOB_NOT_FOUND;
import static uk.gov.moj.cp.metadata.check.util.BlobStatus.INVALID_DOCUMENT_ID;
import static uk.gov.moj.cp.metadata.check.util.BlobStatus.MANDATORY_DOCUMENT_ID;
import static uk.gov.moj.cp.metadata.check.util.StringUtils.isNullOrBlank;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for extracting and validating blob metadata.
 */
public class BlobMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(BlobMetadataService.class);
    private static final String DOCUMENT_ID = "document_id";
    private final BlobClientFactory blobClientFactory;
    private final OutcomeStorageService outcomeStorageService;


    public BlobMetadataService(final String storageConnectionString,
                               final String documentContainerName,
                               final String documentIngestionOutcomeTable
    ) {
        this.blobClientFactory = new BlobClientFactory(storageConnectionString, documentContainerName);
        this.outcomeStorageService = new OutcomeStorageService(storageConnectionString, documentIngestionOutcomeTable);
    }

     public BlobMetadataService(BlobClientFactory blobClientFactory, OutcomeStorageService outcomeStorageService) {
        this.blobClientFactory = blobClientFactory;
        this.outcomeStorageService = outcomeStorageService;
    }

    public Map<String, String> processBlobMetadata(String documentName) {
        try {
            BlobClient blobClient = blobClientFactory.getBlobClient(documentName);
            if (!blobClient.exists()) {
                recordFailure(documentName, BLOB_NOT_FOUND.name(), BLOB_NOT_FOUND.getReason());
                throw new MetadataValidationException("Blob does not exist: " + documentName);
            }

            Map<String, String> metadata = new HashMap<>(blobClient.getProperties().getMetadata());
            validateMetadata(metadata, documentName);
            return metadata;

        } catch (Exception e) {
            logger.error("Failed to extract metadata for blob: {}", documentName, e);
            throw new MetadataValidationException("Failed to extract metadata for blob: " + documentName);
        }

    }

    private void validateMetadata(Map<String, String> metadata, String documentName) {
        String documentId = metadata.get(DOCUMENT_ID);
        if (isNullOrBlank(documentId)) {
            recordFailure(documentName, MANDATORY_DOCUMENT_ID.name(), MANDATORY_DOCUMENT_ID.getReason());
            throw new MetadataValidationException("document_id is required but not found");
        }
        try {
            fromString(documentId);
        } catch (IllegalArgumentException e) {
            recordFailure(documentName, INVALID_DOCUMENT_ID.name(), INVALID_DOCUMENT_ID.getReason());
            throw new MetadataValidationException("Invalid document_id format: " + documentId);
        }
    }

    private void recordFailure(String documentName, String status, String reason) {
        DocumentIngestionOutcome documentIngestionOutcome = new DocumentIngestionOutcome();
        documentIngestionOutcome.setDocumentName(documentName);
        documentIngestionOutcome.setStatus(status);
        documentIngestionOutcome.setReason(reason);
        documentIngestionOutcome.setTimestamp(Instant.now().toString());
        outcomeStorageService.store(documentIngestionOutcome);
    }
}


