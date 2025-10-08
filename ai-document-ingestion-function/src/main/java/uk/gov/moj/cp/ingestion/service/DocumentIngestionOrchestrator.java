package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.requireNonNull;
import static uk.gov.moj.cp.ai.util.DocumentStatus.INGESTION_FAILED;
import static uk.gov.moj.cp.ai.util.DocumentStatus.INGESTION_SUCCESS;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Orchestrates the Document Ingestion Process
 */
public class DocumentIngestionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionOrchestrator.class);

    private final TableStorageService tableStorageService;
    private final DocumentAnalysisService documentAnalysisService;

    public DocumentIngestionOrchestrator() {
        this.documentAnalysisService = new DocumentAnalysisService(
                System.getenv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT"),
                System.getenv("AZURE_DOCUMENT_INTELLIGENCE_KEY")
        );
        this.tableStorageService = new TableStorageService(
                System.getenv("AI_RAG_SERVICE_STORAGE_ACCOUNT"),
                System.getenv("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME")
        );
    }

    public DocumentIngestionOrchestrator(TableStorageService tableStorageService,
                                         final DocumentAnalysisService documentAnalysisService) {
        this.tableStorageService = tableStorageService;
        this.documentAnalysisService = documentAnalysisService;
    }

    public void processQueueMessage(String queueMessage)
            throws DocumentProcessingException {

        LOGGER.info("Starting document ingestion process for queueMessage: {}", queueMessage);

        QueueIngestionMetadata queueIngestionMetadata = null;

        try {
            // parse the queue message
            queueIngestionMetadata = getObjectMapper().readValue(queueMessage, QueueIngestionMetadata.class);

            LOGGER.info("Parsed ingestion metadata - ID: {}, Name: {}, Blob URL: {}",
                    queueIngestionMetadata.documentId(),
                    queueIngestionMetadata.documentName(),
                    queueIngestionMetadata.blobUrl());

            // process the message using document intelligence
            documentAnalysisService.analyzeDocument(queueIngestionMetadata);

            // Step 2: Chunk document using LangChain4j


            // Step 3: Generate embeddings for chunks


            // Step 4: Store chunks in Azure Search

            recordOutcome(queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId(),
                    INGESTION_SUCCESS.name(), INGESTION_SUCCESS.getReason());

            LOGGER.info("Document ingestion completed successfully for document: {} (ID: {})",
                    queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId());

        } catch (Exception e) {

            requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");
            recordOutcome(queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId(),
                    INGESTION_FAILED.name(), INGESTION_FAILED.getReason());

            LOGGER.error("Document ingestion failed for document: {} (ID: {})",
                    queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId(), e);
        }
    }

    private void recordOutcome(String documentName,
                               String documentId,
                               String status,
                               String reason) {

        tableStorageService.upsertDocumentOutcome(documentName, documentId, status, reason);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, documentId);
    }

}

