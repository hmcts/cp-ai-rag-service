package uk.gov.moj.cp.metadata.check.service;


import static uk.gov.moj.cp.ai.util.DocumentStatus.QUEUE_FAILED;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.OutputBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IngestionOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOrchestratorService.class);
    private static final String DOCUMENT_ID = "document_id";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DocumentMetadataService documentMetadataService;

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService) {
        this.documentMetadataService = documentMetadataService;
    }

    public void processDocument(String documentName,
                                OutputBinding<String> successMessage,
                                OutputBinding<DocumentIngestionOutcome> failureOutcome) {

        Map<String, String> metadata;
        String documentId = null;
        try {

            metadata = documentMetadataService.processDocumentMetadata(documentName);
            documentId = metadata.get(DOCUMENT_ID);

            LOGGER.info("Enqueuing document {} with documentId {}", documentName, documentId);

            QueueIngestionMetadata queueMessage = createQueueMessage(documentName, metadata);
            successMessage.setValue(objectMapper.writeValueAsString(queueMessage));

            LOGGER.info("Successfully enqueued document {}", documentName);

        } catch (MetadataValidationException ex) {
            // Record Blob failures
            DocumentIngestionOutcome outcome = documentMetadataService.createInvalidMetadataOutcome(documentName, documentId);
            failureOutcome.setValue(outcome);
            LOGGER.warn("event=metadata_validation_failed documentName={} reason={}",
                    documentName, ex.getMessage());
        } catch (Exception ex) {
            // Record queue processing failures
            DocumentIngestionOutcome outcome = createQueueFailureOutcome(documentName, documentId);
            failureOutcome.setValue(outcome);
            LOGGER.error("event=queue_processing_failed documentName={} documentId={} error={}",
                    documentName, documentId, ex.getMessage(), ex);
        }

    }

    private QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> metadata) {
        String documentId = metadata.get(DOCUMENT_ID);
        String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
        String containerName = System.getenv("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME");
        String blobUrl = String.format("https://%s.blob.core.windows.net/%s/%s", storageAccountName, containerName, blobName);
        String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(
                documentId,
                blobName,
                metadata,
                blobUrl,
                currentTimestamp
        );
    }

    private DocumentIngestionOutcome createQueueFailureOutcome(String documentName, final String documentId) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();

        outcome.generateDefaultPartitionKey();
        outcome.generateRowKeyFrom(documentName);
        outcome.setDocumentName(documentName);
        outcome.setDocumentId(documentId);
        outcome.setStatus(QUEUE_FAILED.name());
        outcome.setReason(QUEUE_FAILED.getReason());
        outcome.setTimestamp(Instant.now().toString());

        return outcome;
    }
}
