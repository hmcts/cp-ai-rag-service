package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.model.DocumentStatus.INVALID_METADATA;
import static uk.gov.moj.cp.ai.model.DocumentStatus.METADATA_VALIDATED;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.DataRetrievalException;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.OutputBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the document ingestion process: 1. Validates metadata from the blob. 2. Sends
 * message to Azure Queue if valid. 3. Records success/failure in Azure Table Storage.
 */
public class IngestionOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOrchestratorService.class);
    private static final String DOCUMENT_ID = "document_id";
    private static final String UNKNOWN_DOCUMENT = "UNKNOWN_DOCUMENT";

    private static final String DUPLICATE_RECORD_LOG_MESSAGE = "Duplicate record found when attempting to record outcome for document '{}'.  Skipping remainder of ingestion.";

    private final DocumentMetadataService documentMetadataService;
    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService) {
        this.documentMetadataService = documentMetadataService;
        String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);
        this.documentIngestionOutcomeTableService = new DocumentIngestionOutcomeTableService(tableName);
    }

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService, DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService) {
        this.documentMetadataService = documentMetadataService;
        this.documentIngestionOutcomeTableService = documentIngestionOutcomeTableService;
    }

    public void processDocument(final String documentName, final OutputBinding<String> queueMessage) {

        try {

            if (isDocumentAlreadyProcessed(documentName)) return;

            final Map<String, String> metadata = documentMetadataService.processDocumentMetadata(documentName);

            final String documentId = metadata.get(DOCUMENT_ID);

            LOGGER.info("Metadata for document '{}' with ID '{}' validated successfully", documentName, documentId);

            final QueueIngestionMetadata queueIngestionMetadata = createQueueMessage(documentName, metadata);

            recordOutcome(documentName, documentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());

            queueMessage.setValue(getObjectMapper().writeValueAsString(queueIngestionMetadata));
            LOGGER.info("Message placed on queue for ingestion for document '{}' with ID '{}'", documentName, documentId);

        } catch (final MetadataValidationException ex) {
            LOGGER.error("Metadata validation failed for document '{}' for reason '{}'", documentName, ex.getMessage());
            try {
                recordOutcome(documentName, UNKNOWN_DOCUMENT, INVALID_METADATA.name(), ex.getMessage());
            } catch (DuplicateRecordException e) {
                LOGGER.info(DUPLICATE_RECORD_LOG_MESSAGE, documentName);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize message and publish to queue for document '" + documentName + "'", e);
        } catch (DuplicateRecordException e) {
            LOGGER.info(DUPLICATE_RECORD_LOG_MESSAGE, documentName);
        }
    }


    public boolean isDocumentAlreadyProcessed(final String documentName) {
        try {
            final DocumentIngestionOutcome firstDocumentMatching = documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName);
            if (null != firstDocumentMatching) {
                LOGGER.info("Document '{}' is already processed and has status '{}'.  Skipping further processing", documentName, firstDocumentMatching.getStatus());
                return true;
            }
        } catch (EntityRetrievalException e) {
            throw new DataRetrievalException("Unable to check status of document in table storage", e);
        }
        return false;
    }

    /**
     * Builds a queue message with document metadata and blob details.
     */
    private QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> metadata) {
        final String documentId = metadata.get(DOCUMENT_ID);
        final String blobStorageEndpoint = removeTrailingSlash(System.getenv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT));
        final String containerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME);
        final String blobUrl = String.format("%s/%s/%s", blobStorageEndpoint, containerName, blobName);
        final String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(documentId, blobName, metadata, blobUrl, currentTimestamp);
    }

    /**
     * Records a document ingestion outcome (success or failure) in Table Storage.
     */
    private void recordOutcome(String documentName,
                               String documentId,
                               String status,
                               String reason) throws DuplicateRecordException {
        final String effectiveDocumentId = isNullOrEmpty(documentId) ? UNKNOWN_DOCUMENT : documentId.trim();
        documentIngestionOutcomeTableService.insertIntoTable(documentName, effectiveDocumentId, status, reason);

        LOGGER.info("Status for document '{}' with ID '{}' updated to '{}'", documentName, effectiveDocumentId, status);
    }

    /**
     * Records a document ingestion outcome (success or failure) in Table Storage.
     */
    public void recordUploadInitiated(final String documentName, final String documentId) throws DuplicateRecordException {
        final String effectiveDocumentId = isNullOrEmpty(documentId) ? UNKNOWN_DOCUMENT : documentId.trim();

        documentIngestionOutcomeTableService.insertUploadInitiated(documentName, effectiveDocumentId, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());

        LOGGER.info("Status for document '{}' with ID '{}' updated to '{}'", documentName, effectiveDocumentId, METADATA_VALIDATED.name());
    }
}