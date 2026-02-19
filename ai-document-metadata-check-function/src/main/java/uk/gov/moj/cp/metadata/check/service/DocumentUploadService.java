package uk.gov.moj.cp.metadata.check.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.model.DocumentStatus.AWAITING_UPLOAD;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.DataRetrievalException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the document ingestion process: 1. Validates metadata from the blob. 2. Sends
 * message to Azure Queue if valid. 3. Records success/failure in Azure Table Storage.
 */
public class DocumentUploadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadService.class);
    private static final String UNKNOWN_DOCUMENT = "UNKNOWN_DOCUMENT";

    public static final String DUPLICATE_RECORD_LOG_MESSAGE = "Duplicate record found when attempting to initiate document upload for document '{}'.  Skipping remainder of ingestion.";

    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    public DocumentUploadService() {
        String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);
        this.documentIngestionOutcomeTableService = new DocumentIngestionOutcomeTableService(tableName);
    }

    public DocumentUploadService(DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService) {
        this.documentIngestionOutcomeTableService = documentIngestionOutcomeTableService;
    }

    /**
     * Check if documentId already processed and recorded in Table Storage.
     */
    public boolean isDocumentAlreadyProcessed(final String documentId) {
        try {
            final DocumentIngestionOutcome firstDocumentMatching = documentIngestionOutcomeTableService.getDocumentById(documentId);
            if (null != firstDocumentMatching) {
                LOGGER.info("Document '{}' is already processed and has status '{}'.  Skipping document initiation...", documentId, firstDocumentMatching.getStatus());
                return true;
            }
        } catch (EntityRetrievalException e) {
            throw new DataRetrievalException("Unable to check status of document in table storage", e);
        }
        return false;
    }

    /**
     * Records a document ingestion outcome (success or failure) in Table Storage.
     */
    public void recordUploadInitiated(final String documentName, final String documentId) {
        try {
            documentIngestionOutcomeTableService.insertWithDocumentId(documentName, documentId, AWAITING_UPLOAD.name(), AWAITING_UPLOAD.getReason());
        } catch (DuplicateRecordException dre) {
            LOGGER.info(DUPLICATE_RECORD_LOG_MESSAGE, documentName);
        }
    }
}