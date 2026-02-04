package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.requireNonNull;
import static uk.gov.moj.cp.ai.model.DocumentStatus.INGESTION_SUCCESS;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.FunctionEnvironment;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

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

        final FunctionEnvironment env = FunctionEnvironment.get();
        // Document Intelligence Configuration
        final String documentIntelligenceEndpoint = getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT");

        // Storage Configuration
        final String tableDocumentIngestionOutcome = env.tableConfig().documentIngestionOutcomeTable();

        // Search Service Configuration
        final String azureSearchServiceEndpoint = env.searchConfig().serviceEndpoint();
        final String azureSearchIndexName = env.searchConfig().serviceIndexName();

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

    public void processQueueMessage(QueueIngestionMetadata queueIngestionMetadata)
            throws DocumentProcessingException {

        requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");

        String documentName = queueIngestionMetadata.documentName();
        String documentId = queueIngestionMetadata.documentId();
        String documentUrl = queueIngestionMetadata.blobUrl();

        LOGGER.info("Starting document ingestion process for document: {} (ID: {})", documentName, documentId);
        // Step 1: Analyze document using Azure Document Intelligence
        AnalyzeResult analyzeResult = documentIntelligenceService.analyzeDocument(documentName, documentUrl);

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

        documentIngestionOutcomeTableService.upsertIntoTable(documentName, documentId, status, reason);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, documentId);
    }

}

