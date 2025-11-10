package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
import static uk.gov.moj.cp.ai.util.DocumentStatus.INVALID_METADATA;
import static uk.gov.moj.cp.ai.util.DocumentStatus.METADATA_VALIDATED;
import static uk.gov.moj.cp.ai.util.DocumentStatus.QUEUE_FAILED;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;
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
    private static final String UNKNOWN_DOCUMENT = "UNKNOWN_DOCUMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DocumentMetadataService documentMetadataService;
    private final TableStorageService tableStorageService;

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService) {
        this.documentMetadataService = documentMetadataService;
        // Initialize TableStorageService to ensure consistent column names
        // This bypasses @TableOutput binding which doesn't respect @JsonProperty annotations
        String storageAccount = System.getenv("AI_RAG_SERVICE_STORAGE_ACCOUNT");
        String tableName = System.getenv("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME");
        this.tableStorageService = new TableStorageService(storageAccount, tableName);
    }

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService, TableStorageService tableStorageService) {
        this.documentMetadataService = documentMetadataService;
        this.tableStorageService = tableStorageService;
    }

    /**
     * Main orchestration logic for blob ingestion.
     */
    public void processDocument(String documentName,
                                OutputBinding<String> queueMessage) {

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

            recordOutcome(documentName, documentId,
                    METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());

        } catch (MetadataValidationException ex) {
            LOGGER.warn("event=metadata_validation_failed documentName={} documentId={} reason={}",
                    documentName, documentId, ex.getMessage());
            if (tableStorageService != null) {
                recordOutcome(documentName,
                        documentId != null ? documentId : UNKNOWN_DOCUMENT,
                        INVALID_METADATA.name(),
                        ex.getMessage());
            } else {
                LOGGER.error("event=tableStorageService_null documentName={} reason={}", documentName, ex.getMessage());
            }

        } catch (Exception ex) {
            LOGGER.error("event=queue_processing_failed documentName={} documentId={} error={}",
                    documentName, documentId, ex.getMessage(), ex);

            if (tableStorageService != null) {
                recordOutcome(documentName,
                        documentId != null ? documentId : UNKNOWN_DOCUMENT,
                        QUEUE_FAILED.name(),
                        ex.getMessage());
            }
        }
    }

    /**
     * Builds a queue message with document metadata and blob details.
     */
    private QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> metadata) {
        String documentId = metadata.get(DOCUMENT_ID);
        String blobStorageEndpoint = System.getenv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);
        String containerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME);
        String blobUrl = String.format("%s/%s/%s",
                blobStorageEndpoint, containerName, blobName);
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
    private void recordOutcome(String documentName,
                               String documentId,
                               String status,
                               String reason) {
        // TODO need to change the partition and rowkey
        String effectiveDocumentId = isNullOrEmpty(documentId) ? UNKNOWN_DOCUMENT : documentId.trim();
        tableStorageService.upsertDocumentOutcome(documentName, effectiveDocumentId, status, reason);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, effectiveDocumentId);
    }
}