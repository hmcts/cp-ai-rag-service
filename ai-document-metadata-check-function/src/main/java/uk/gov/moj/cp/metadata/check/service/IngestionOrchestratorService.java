package uk.gov.moj.cp.metadata.check.service;


import static uk.gov.moj.cp.metadata.check.util.DocumentStatus.METADATA_VALIDATED;
import static uk.gov.moj.cp.metadata.check.util.DocumentStatus.QUEUE_FAILED;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;
import uk.gov.moj.cp.metadata.check.exception.QueueSendException;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IngestionOrchestratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOrchestratorService.class);

    private final DocumentMetadataService documentMetadataService;
    private final QueueStorageService queueStorageService;
    private final TableStorageService tableStorageService;

    public IngestionOrchestratorService(DocumentMetadataService documentMetadataService,
                                        QueueStorageService queueStorageService,
                                        TableStorageService tableStorageService) {
        this.documentMetadataService = documentMetadataService;
        this.queueStorageService = queueStorageService;
        this.tableStorageService = tableStorageService;
    }

    public void processDocument(String documentName) {

        Map<String, String> metadata;
        String documentId = null;
        try {

            metadata = documentMetadataService.processDocumentMetadata(documentName);
            documentId = metadata.get("document_id");

            LOGGER.info("Enqueuing document {} with documentId {}", documentName, documentId);

            queueStorageService.sendToQueue(documentName, metadata);

            recordOutcome(documentId, documentName, METADATA_VALIDATED.name(), METADATA_VALIDATED.getReason());
            LOGGER.info("Successfully enqueued and recorded outcome for document {}", documentName);

        } catch (MetadataValidationException ex) {

            LOGGER.warn("Metadata validation failed for blob {}: {}", documentName, ex.getMessage());
        } catch (QueueSendException ex) {

            recordOutcome(documentId, documentName, QUEUE_FAILED.name(), ex.getMessage());
            LOGGER.error("Failed to enqueue blob {}: {}", documentName, ex.getMessage(), ex);
        }

    }

    private void recordOutcome(String documentId, String documentName,
                               String status, String reason) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setDocumentId(documentId);
        outcome.setDocumentName(documentName);
        outcome.setStatus(status);
        outcome.setReason(reason);
        outcome.setTimestamp(Instant.now().toString());
        tableStorageService.recordOutcome(outcome);
    }
}
