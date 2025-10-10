package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.requireNonNull;
import static uk.gov.moj.cp.ai.util.DocumentStatus.INGESTION_FAILED;
import static uk.gov.moj.cp.ai.util.DocumentStatus.INGESTION_SUCCESS;

import uk.gov.moj.cp.ai.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.EmbeddingService;
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
    private final EmbeddingService embeddingService;
    private final DocumentStorageService documentStorageService;

    public DocumentIngestionOrchestrator() {

        // Embedding Service Configuration
        String embeddingServiceEndpoint = getRequiredEnv("AZURE_EMBEDDING_SERVICE_ENDPOINT");
        String embeddingServiceApiKey = getRequiredEnv("AZURE_EMBEDDING_SERVICE_API_KEY");
        String embeddingServiceDeploymentName = getRequiredEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME");

        // Document Intelligence Configuration
        String azureDocumentIntelligenceEndpoint = getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT");
        String azureDocumentIntelligenceKey = getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_KEY");

        // Storage Configuration
        String aiRagServiceStorageAccount = getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT");
        String storageAccountTableDocumentIngestionOutcome = getRequiredEnv("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME");

        // Search Service Configuration
        String azureSearchServiceDocumentEndpoint = getRequiredEnv("AZURE_SEARCH_SERVICE_DOCUMENT_ENDPOINT");
        String azureSearchIndexDocumentName = getRequiredEnv("AZURE_SEARCH_INDEX_DOCUMENT_NAME");
        String azureSearchDocumentAdminKey = getRequiredEnv("AZURE_SEARCH_DOCUMENT_ADMIN_KEY");

        // document analysis service
        this.documentAnalysisService = new DocumentAnalysisService(
                azureDocumentIntelligenceEndpoint,
                azureDocumentIntelligenceKey
        );

        // table storage service
        this.tableStorageService = new TableStorageService(
                aiRagServiceStorageAccount,
                storageAccountTableDocumentIngestionOutcome
        );

        this.documentChunkingService = new DocumentChunkingService();

        this.embeddingService = new EmbeddingService(
                embeddingServiceEndpoint,
                embeddingServiceApiKey,
                embeddingServiceDeploymentName

        );

        this.documentStorageService = new DocumentStorageService(
                azureSearchServiceDocumentEndpoint,
                azureSearchIndexDocumentName,
                azureSearchDocumentAdminKey

        );
    }

    public DocumentIngestionOrchestrator(final TableStorageService tableStorageService,
                                         final DocumentAnalysisService documentAnalysisService,
                                         final DocumentChunkingService documentChunkingService,
                                         final EmbeddingService embeddingService,
                                         final DocumentStorageService documentStorageService) {
        this.tableStorageService = requireNonNull(tableStorageService, "TableStorageService must not be null");
        this.documentAnalysisService = requireNonNull(documentAnalysisService, "DocumentAnalysisService must not be null");
        this.documentChunkingService = requireNonNull(documentChunkingService, "DocumentChunkingService must not be null");
        this.embeddingService = requireNonNull(embeddingService, "EmbeddingService must not be null");
        this.documentStorageService = requireNonNull(documentStorageService, "DocumentStorageService must not be null");
    }

    public void processQueueMessage(QueueIngestionMetadata queueIngestionMetadata)
            throws DocumentProcessingException {

        requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");

        String documentName = queueIngestionMetadata.documentName();
        String documentId = queueIngestionMetadata.documentId();
        String documentUrl = queueIngestionMetadata.blobUrl();

        LOGGER.info("Starting document ingestion process for document: {} (ID: {})", documentName, documentId);
        try {
            // Step 1: Analyze document using Azure Document Intelligence
            AnalyzeResult analyzeResult = documentAnalysisService.analyzeDocument(documentName,documentUrl);

            // Step 2: Chunk document using LangChain4j
            List<ChunkedEntry> chunkedEntries = documentChunkingService.chunkDocument(analyzeResult, queueIngestionMetadata);

            // Step 3: Generate embeddings for chunks
            enrichChunksWithEmbeddings(chunkedEntries);

            // Step 4: Store chunks in Azure Search
            documentStorageService.uploadChunks(Collections.unmodifiableList(chunkedEntries));

            // Record success
            recordOutcome(documentName, documentId, INGESTION_SUCCESS.name(), INGESTION_SUCCESS.getReason());

            LOGGER.info("Document ingestion completed successfully for document: {} (ID: {})", documentName, documentId);

        } catch (Exception e) {

            requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");
            recordOutcome(queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId(),
                    INGESTION_FAILED.name(), INGESTION_FAILED.getReason());

            LOGGER.error("Document ingestion failed for document: {} (ID: {})",
                    queueIngestionMetadata.documentName(), queueIngestionMetadata.documentId(), e);
        }
    }

    private void enrichChunksWithEmbeddings(List<ChunkedEntry> chunkedEntries) {
        for (int i = 0; i < chunkedEntries.size(); i++) {
            ChunkedEntry chunkedEntry = chunkedEntries.get(i);
            if (isEmptyChunk(chunkedEntry)) {
                LOGGER.warn("Skipping chunk on page {} - empty or null text", chunkedEntry.pageNumber());
                continue;
            }

            try {
                List<Double> vector = embeddingService.embedStringData(chunkedEntry.chunk());
                
                // Create new ChunkedEntry with the vector
                ChunkedEntry enrichedEntry = ChunkedEntry.builder()
                        .id(chunkedEntry.id())
                        .documentId(chunkedEntry.documentId())
                        .chunk(chunkedEntry.chunk())
                        .chunkVector(vector)
                        .documentFileName(chunkedEntry.documentFileName())
                        .pageNumber(chunkedEntry.pageNumber())
                        .chunkIndex(chunkedEntry.chunkIndex())
                        .documentFileUrl(chunkedEntry.documentFileUrl())
                        .customMetadata(chunkedEntry.customMetadata())
                        .build();
                
                // Replace the entry in the list
                chunkedEntries.set(i, enrichedEntry);

                LOGGER.debug("Generated embedding for page {} with vector size {}",
                        chunkedEntry.pageNumber(), vector.size());

            } catch (EmbeddingServiceException e) {
                LOGGER.error("Failed to embed chunk on page {}: {}",
                        chunkedEntry.pageNumber(), e.getMessage());
                // Continue processing other chunks instead of failing completely
            }
        }
    }

    private boolean isEmptyChunk(ChunkedEntry chunk) {
        return chunk.chunk() == null || chunk.chunk().trim().isEmpty();
    }

    private void recordOutcome(String documentName,
                               String documentId,
                               String status,
                               String reason) {

        tableStorageService.upsertDocumentOutcome(documentName, documentId, status, reason);

        LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}",
                status, documentName, documentId);
    }

    private static String getRequiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Required environment variable not set: " + key);
        }
        return value;
    }

}

