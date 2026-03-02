package uk.gov.moj.cp.metadata.check.service;

import static java.util.Objects.nonNull;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.AWAITING_INGESTION;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.AWAITING_UPLOAD;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.metadata.check.exception.DataRetrievalException;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentUploadService {

    public static final String UPLOAD_ALREADY_INITIATED_ERROR = "An upload request has already been initiated for documentId: '%s'";
    public static final String DUPLICATE_RECORD_ERROR = "Duplicate record found when attempting to initiate document upload for documentId: '%s'";

    static final String AWAITING_UPLOAD_REASON = "Upload initiated, document awaiting upload";
    static final String AWAITING_INGESTION_REASON = "Upload complete, document awaiting ingestion";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadService.class);
    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    public DocumentUploadService() {
        String tableName = getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);
        this.documentIngestionOutcomeTableService = new DocumentIngestionOutcomeTableService(tableName);
    }

    public DocumentUploadService(DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService) {
        this.documentIngestionOutcomeTableService = documentIngestionOutcomeTableService;
    }

    /**
     * Check if documentId already recorded in Table Storage.
     */
    public boolean isDocumentAlreadyProcessed(final String documentId) {
        final DocumentIngestionOutcome firstDocumentMatching = getDocument(documentId);
        if (nonNull(firstDocumentMatching)) {
            LOGGER.info("Document '{}' is already processed and has status '{}'.", documentId, firstDocumentMatching.getStatus());
            return true;
        }
        return false;
    }

    /**
     * A new document record is added to the table storage with status AWAITING_UPLOAD.
     */
    public void addDocumentAwaitingUpload(final String documentId, final String documentName, final Map<String, String> metadataMap) throws DuplicateRecordException {
        final String metadataString = convert(metadataMap);
        documentIngestionOutcomeTableService.insert(documentId, documentName, metadataString, AWAITING_UPLOAD.name(), AWAITING_UPLOAD_REASON);
    }

    /**
     * upsert the document record in the table storage with status AWAITING_INGESTION.
     */
    public void updateDocumentAwaitingIngestion(final String documentId, final String documentName) {
        documentIngestionOutcomeTableService.upsertDocument(documentId, documentName, AWAITING_INGESTION.name(), AWAITING_INGESTION_REASON);
    }

    /**
     * Get Document by documentId from the Table Storage.
     */
    public DocumentIngestionOutcome getDocument(final String documentId) {
        try {
            return documentIngestionOutcomeTableService.getDocumentById(documentId);
        } catch (EntityRetrievalException e) {
            throw new DataRetrievalException("Unable to check status of document in table storage", e);
        }
    }
}