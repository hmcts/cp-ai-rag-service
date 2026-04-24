package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.model.DocumentStatus.INGESTION_FAILED;
import static uk.gov.moj.cp.ai.model.DocumentStatus.INGESTION_SUCCESS;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the Document Ingestion Process
 */
public class DocumentIngestionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionOrchestrator.class);

    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;
    private final DocumentIntelligenceService documentIntelligenceService;
    private final DocumentChunkingService documentChunkingService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final DocumentStorageService documentStorageService;

    public DocumentIngestionOrchestrator() {

        // Document Intelligence Configuration
        String documentIntelligenceEndpoint = getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT");

        // Storage Configuration
        String tableDocumentIngestionOutcome = getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);

        // Search Service Configuration
        String azureSearchServiceEndpoint = getRequiredEnv(AZURE_SEARCH_SERVICE_ENDPOINT);
        String azureSearchIndexName = getRequiredEnv(AZURE_SEARCH_SERVICE_INDEX_NAME);

        this.documentIntelligenceService = new DocumentIntelligenceService(documentIntelligenceEndpoint);

        this.documentIngestionOutcomeTableService = new DocumentIngestionOutcomeTableService(tableDocumentIngestionOutcome);

        this.documentChunkingService = new DocumentChunkingService();

        this.chunkEmbeddingService = new ChunkEmbeddingService();

        this.documentStorageService = new DocumentStorageService(azureSearchServiceEndpoint, azureSearchIndexName);
    }

    public DocumentIngestionOrchestrator(final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService,
                                         final DocumentIntelligenceService documentIntelligenceService,
                                         final DocumentChunkingService documentChunkingService,
                                         final ChunkEmbeddingService chunkEmbeddingService,
                                         final DocumentStorageService documentStorageService) {
        this.documentIngestionOutcomeTableService = requireNonNull(documentIngestionOutcomeTableService, "TableStorageService must not be null");
        this.documentIntelligenceService = requireNonNull(documentIntelligenceService, "DocumentAnalysisService must not be null");
        this.documentChunkingService = requireNonNull(documentChunkingService, "DocumentChunkingService must not be null");
        this.chunkEmbeddingService = requireNonNull(chunkEmbeddingService, "ChunkEmbeddingService must not be null");
        this.documentStorageService = requireNonNull(documentStorageService, "DocumentStorageService must not be null");
    }

    public void processQueueMessage(final QueueIngestionMetadata queueIngestionMetadata)
            throws DocumentProcessingException {

        requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");

        final String documentName = queueIngestionMetadata.documentName();
        final String documentId = queueIngestionMetadata.documentId();
        final String documentUrl = queueIngestionMetadata.blobUrl();
        final boolean isDocumentIdAsRowKey = queueIngestionMetadata.isDocumentIdUsedAsRowKey();


        LOGGER.info("Starting document ingestion process for document: {} (ID: {})", documentName, documentId);
        // Step 1: Analyze document using Azure Document Intelligence
        AnalyzeResult analyzeResult = documentIntelligenceService.analyzeDocument(documentName, documentUrl);

        // Step 2: Chunk document using LangChain4j
        List<ChunkedEntry> chunkedEntries = documentChunkingService.chunkDocument(analyzeResult, queueIngestionMetadata);

        // Step 3: Generate embeddings for chunks
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // Step 4: Store chunks in Azure Search
        documentStorageService.uploadChunks(Collections.unmodifiableList(chunkedEntries));

        // Step 5: Mark superseded documents inActive
        markSupersededDocumentsInActive(documentId);

        // Step 6: Record success
        recordOutcome(documentName, documentId, INGESTION_SUCCESS.name(), INGESTION_SUCCESS.getReason(), isDocumentIdAsRowKey);

        LOGGER.info("Document ingestion completed successfully for document: {} (ID: {})", documentName, documentId);

    }

    public void processQueueMessageFailed(final String queueMessage) {

        if (isNullOrEmpty(queueMessage)) {
            LOGGER.error("Invalid queue queueMessage received: {}, document outcome cannot be updated.", queueMessage);
            return;
        }

        try {
            final QueueIngestionMetadata queueIngestionMetadata = getObjectMapper().readValue(queueMessage, QueueIngestionMetadata.class);
            final String documentName = queueIngestionMetadata.documentName();
            final String documentId = queueIngestionMetadata.documentId();
            final boolean isDocumentIdAsRowKey = queueIngestionMetadata.isDocumentIdUsedAsRowKey();

            recordOutcome(documentName, documentId, INGESTION_FAILED.name(), INGESTION_FAILED.getReason(), isDocumentIdAsRowKey);

        } catch (Exception e) {
            LOGGER.error("Error processing queue message: {}, document outcome cannot be updated.", queueMessage, e);
        }
    }

    private void markSupersededDocumentsInActive(final String documentId) throws DocumentProcessingException {
        try {
            final DocumentIngestionOutcome document = documentIngestionOutcomeTableService.getDocumentById(documentId);
            if (nonNull(document) && !isNullOrEmpty(document.getSupersededDocuments())) {
                final List<String> supersededDocs = Arrays.stream(document.getSupersededDocuments().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                documentStorageService.markDocumentsInActive(supersededDocs);
            }
        } catch (EntityRetrievalException e) {
            final String message = String.format("Failed to get document with Id: '%s', superseded documents this document may have are not marked inactive in Search Index", documentId);
            throw new DocumentProcessingException(message, e);
        }
    }

    private void recordOutcome(final String documentName, final String documentId,
                               final String status, final String reason,
                               boolean isDocumentIdAsRowKey) throws DocumentProcessingException {
        try {
            if (isDocumentIdAsRowKey) {
                documentIngestionOutcomeTableService.upsertDocument(documentId, status, reason);
            } else {
                documentIngestionOutcomeTableService.upsertIntoTable(documentName, documentId, status, reason);
            }
            LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}", status, documentName, documentId);
        } catch (Exception e) {
            final String message = String.format("Failed to update document outcome '%s' for the documentId: '%s'", status, documentId);
            throw new DocumentProcessingException(message, e);
        }
    }

}

