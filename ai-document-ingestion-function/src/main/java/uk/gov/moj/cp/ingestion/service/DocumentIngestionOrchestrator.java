package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.requireNonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.model.DocumentStatus.INGESTION_SUCCESS;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.Collections;
import java.util.List;

import com.azure.ai.formrecognizer.documentanalysis.models.AnalyzeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the Document Ingestion Process
 */
public class DocumentIngestionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionOrchestrator.class);

    private final TableStorageService tableStorageService;
    private final DocumentAnalysisService documentAnalysisService;
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

        this.documentAnalysisService = new DocumentAnalysisService(documentIntelligenceEndpoint);

        this.tableStorageService = new TableStorageService(tableDocumentIngestionOutcome);

        this.documentChunkingService = new DocumentChunkingService();

        this.chunkEmbeddingService = new ChunkEmbeddingService();

        this.documentStorageService = new DocumentStorageService(azureSearchServiceEndpoint, azureSearchIndexName);
    }

    public DocumentIngestionOrchestrator(final TableStorageService tableStorageService,
                                         final DocumentAnalysisService documentAnalysisService,
                                         final DocumentChunkingService documentChunkingService,
                                         final ChunkEmbeddingService chunkEmbeddingService,
                                         final DocumentStorageService documentStorageService) {
        this.tableStorageService = requireNonNull(tableStorageService, "TableStorageService must not be null");
        this.documentAnalysisService = requireNonNull(documentAnalysisService, "DocumentAnalysisService must not be null");
        this.documentChunkingService = requireNonNull(documentChunkingService, "DocumentChunkingService must not be null");
        this.chunkEmbeddingService = requireNonNull(chunkEmbeddingService, "ChunkEmbeddingService must not be null");
        this.documentStorageService = requireNonNull(documentStorageService, "DocumentStorageService must not be null");
    }

    public void processQueueMessage(QueueIngestionMetadata queueIngestionMetadata)
            throws DocumentProcessingException {

        requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");

        String documentName = queueIngestionMetadata.documentName();
        String documentId = queueIngestionMetadata.documentId();
        String documentUrl = queueIngestionMetadata.blobUrl();

        LOGGER.info("Starting document ingestion process for document: {} (ID: {})", documentName, documentId);
        // Step 1: Analyze document using Azure Document Intelligence
        AnalyzeResult analyzeResult = documentAnalysisService.analyzeDocument(documentName, documentUrl);

        // Step 2: Chunk document using LangChain4j
        List<ChunkedEntry> chunkedEntries = documentChunkingService.chunkDocument(analyzeResult, queueIngestionMetadata);

        // Step 3: Generate embeddings for chunks
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // Step 4: Store chunks in Azure Search
        documentStorageService.uploadChunks(Collections.unmodifiableList(chunkedEntries));

        // Record success
        recordOutcome(documentName, documentId, INGESTION_SUCCESS.name(), INGESTION_SUCCESS.getReason());

        LOGGER.info("Document ingestion completed successfully for document: {} (ID: {})", documentName, documentId);

    }


    private void recordOutcome(String documentName,
                               String documentId,
                               String status,
                               String reason) {

        tableStorageService.upsertIntoTable(documentName, documentId, status, reason);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, documentId);
    }

}

