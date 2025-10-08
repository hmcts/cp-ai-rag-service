package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.util.DocumentStatus.INVALID_METADATA;
import static uk.gov.moj.cp.ai.util.DocumentStatus.METADATA_VALIDATED;
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

/**
 * Orchestrates the document ingestion process:
 * 1. Validates metadata from the blob.
 * 2. Sends message to Azure Queue if valid.
 * 3. Records success/failure in Azure Table Storage.
 */
public class IngestionOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOrchestratorService.class);
    private static final String DOCUMENT_ID = "document_id";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentMetadataService documentMetadataService;

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService) {
        this.documentMetadataService = documentMetadataService;
    }

    /**
     * Main orchestration logic for blob ingestion.
     */
    public void processDocument(String documentName,
                                OutputBinding<String> queueMessage,
                                OutputBinding<DocumentIngestionOutcome> tableOutcome) {

        Map<String, String> metadata;
        String documentId = null;

        try {
            metadata = documentMetadataService.processDocumentMetadata(documentName);
            documentId = metadata.get(DOCUMENT_ID);

            LOGGER.info("event=document_metadata_validated documentName={} documentId={}", documentName, documentId);

            QueueIngestionMetadata queueIngestionMetadata = createQueueMessage(documentName, metadata);
            String serializedMessage = objectMapper.writeValueAsString(queueIngestionMetadata);

            queueMessage.setValue(serializedMessage);
            LOGGER.info("event=document_enqueued documentName={} documentId={}", documentName, documentId);

            recordOutcome(tableOutcome, documentName, documentId,
                    METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());

        } catch (MetadataValidationException ex) {
            recordOutcome(tableOutcome, documentName, documentId,
                    INVALID_METADATA.name(), ex.getMessage());
            LOGGER.warn("event=metadata_validation_failed documentName={} documentId={} reason={}",
                    documentName, documentId, ex.getMessage());

        } catch (Exception ex) {
            recordOutcome(tableOutcome, documentName, documentId,
                    QUEUE_FAILED.name(), ex.getMessage());
            LOGGER.error("event=queue_processing_failed documentName={} documentId={} error={}",
                    documentName, documentId, ex.getMessage(), ex);
        }
    }

    /**
     * Builds a queue message with document metadata and blob details.
     */
    private QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> metadata) {
        String documentId = metadata.get(DOCUMENT_ID);
        String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
        String containerName = System.getenv("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME");
        String blobUrl = String.format("https://%s.blob.core.windows.net/%s/%s",
                storageAccountName, containerName, blobName);
        String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(
                documentId,
                blobName,
                metadata,
                blobUrl,
                currentTimestamp
        );
    }

    /**
     * Records a document ingestion outcome (success or failure) in Table Storage.
     */
    private void recordOutcome(OutputBinding<DocumentIngestionOutcome> outcomeBinding,
                               String documentName,
                               String documentId,
                               String status,
                               String reason) {

        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setPartitionKey(documentId);
        outcome.generateRowKeyFrom(documentName);
        outcome.setDocumentName(documentName);
        outcome.setDocumentId(documentId);
        outcome.setStatus(status);
        outcome.setReason(reason);
        outcome.setTimestamp(Instant.now().toString());

        outcomeBinding.setValue(outcome);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, documentId);
    }
}