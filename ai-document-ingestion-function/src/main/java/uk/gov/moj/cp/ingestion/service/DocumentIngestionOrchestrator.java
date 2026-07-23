package uk.gov.moj.cp.ingestion.service;


import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;
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

    private static final String INGESTION_SUCCESS_REASON = "Document ingestion completed successfully";
    private static final String INGESTION_FAILED_REASON = "Document ingestion failed during processing";

    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;
    private final DocumentIntelligenceService documentIntelligenceService;
    private final DocumentChunkingService documentChunkingService;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final DocumentStorageService documentStorageService;

    public DocumentIngestionOrchestrator() {
        this(new DocumentIngestionOutcomeTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME)));
    }

    /**
     * Builds every collaborator from the environment except the outcome table service, which the
     * function shares with the idempotency guard.
     */
    public DocumentIngestionOrchestrator(final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService) {

        // Document Intelligence Configuration
        String documentIntelligenceEndpoint = getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT");

        // Search Service Configuration
        String azureSearchServiceEndpoint = getRequiredEnv(AZURE_SEARCH_SERVICE_ENDPOINT);
        String azureSearchIndexName = getRequiredEnv(AZURE_SEARCH_SERVICE_INDEX_NAME);

        this.documentIntelligenceService = new DocumentIntelligenceService(documentIntelligenceEndpoint);

        this.documentIngestionOutcomeTableService = documentIngestionOutcomeTableService;

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

    public void processQueueMessage(final QueueIngestionMetadata queueIngestionMetadata, final ClaimToken token)
            throws DocumentProcessingException {

        requireNonNull(queueIngestionMetadata, "Queue ingestion metadata must not be null");

        final String documentName = queueIngestionMetadata.documentName();
        final String documentId = queueIngestionMetadata.documentId();
        final String documentUrl = queueIngestionMetadata.blobUrl();

        LOGGER.info("Starting document ingestion process for document: {} (ID: {})", documentName, documentId);
        // Step 1: Analyze document using Azure Document Intelligence
        AnalyzeResult analyzeResult = documentIntelligenceService.analyzeDocument(documentName, documentUrl);

        // Step 2: Chunk document using LangChain4j
        List<ChunkedEntry> chunkedEntries = documentChunkingService.chunkDocument(analyzeResult, queueIngestionMetadata);

        // Step 3: Generate embeddings for chunks
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // Step 4: Store chunks in Azure Search
        documentStorageService.uploadChunks(Collections.unmodifiableList(chunkedEntries));

        // Step 5: Mark superseded documents inactive
        markSupersededDocumentsInactive(documentId, token);

        // Step 6: Record success (fenced on the claim-time ETag)
        recordOutcome(documentName, documentId, INGESTION_SUCCESS.name(), INGESTION_SUCCESS_REASON, token);

        LOGGER.info("Document ingestion completed successfully for document: {} (ID: {})", documentName, documentId);

    }

    public void processQueueMessageFailed(final QueueIngestionMetadata queueIngestionMetadata, final ClaimToken token)
            throws DocumentProcessingException {
        try {
            final String documentName = queueIngestionMetadata.documentName();
            final String documentId = queueIngestionMetadata.documentId();

            recordOutcome(documentName, documentId, INGESTION_FAILED.name(), INGESTION_FAILED_REASON, token);

        } catch (EtagMismatchException fenceLoss) {
            // Correct by construction: being fenced out means another worker owns the outcome.
            LOGGER.warn("Fenced FAILED write rejected for documentId: {} — another worker owns the outcome.",
                    queueIngestionMetadata.documentId(), fenceLoss);
        }
        // Any other write failure propagates: the invocation fails visibly (poison queue at
        // exhaustion) and the guard releases the lease, instead of silently consuming the
        // message and leaving the row non-terminal with a live lease.
    }

    /**
     * FAILED write for failures where no claim was ever obtained (claim infrastructure errors at
     * exhaustion). Re-checks the row first and writes fenced on the freshly read ETag — it must
     * never overwrite a terminal outcome or a live leaseholder's in-progress work. If even the
     * re-check fails, nothing is written (the row surfaces via alerting instead).
     */
    public void processQueueMessageFailedIfSafe(final QueueIngestionMetadata queueIngestionMetadata, final String clientId) {
        final String documentId = queueIngestionMetadata.documentId();
        try {
            final LeaseSnapshot snapshot = documentIngestionOutcomeTableService.readForClaim(clientId, documentId);
            if (snapshot == null) {
                LOGGER.error("Not recording INGESTION_FAILED for documentId: {} — status row is missing.", documentId);
                return;
            }
            if (documentIngestionOutcomeTableService.isTerminal(snapshot.status())) {
                LOGGER.info("Not recording INGESTION_FAILED for documentId: {} — row is already terminal ({}).", documentId, snapshot.status());
                return;
            }
            if (nonNull(snapshot.leaseExpiresAt()) && snapshot.leaseExpiresAt().isAfter(java.time.OffsetDateTime.now())) {
                LOGGER.warn("Not recording INGESTION_FAILED for documentId: {} — another worker holds a live lease.", documentId);
                return;
            }
            documentIngestionOutcomeTableService.recordOutcomeFenced(
                    clientId, documentId, INGESTION_FAILED.name(), INGESTION_FAILED_REASON, snapshot.etag());

        } catch (EtagMismatchException e) {
            LOGGER.warn("Not recording INGESTION_FAILED for documentId: {} — row changed concurrently; leaving the outcome to its owner.", documentId, e);
        } catch (Exception e) {
            LOGGER.error("Unable to safely record INGESTION_FAILED for documentId: {} — leaving row unchanged.", documentId, e);
        }
    }

    private void markSupersededDocumentsInactive(final String documentId, final ClaimToken token) throws DocumentProcessingException {
        try {
            final DocumentIngestionOutcome document = documentIngestionOutcomeTableService.getDocumentById(token.clientId(), documentId);
            if (nonNull(document) && !isNullOrEmpty(document.getSupersededDocuments())) {
                final List<String> supersededDocs = Arrays.stream(document.getSupersededDocuments().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                documentStorageService.markDocumentsInActive(token.clientId(), supersededDocs);
            }
        } catch (EntityRetrievalException e) {
            final String message = String.format("Unable to mark documents as Inactive in search index which were to be superseded by document with ID: %s", documentId);
            throw new DocumentProcessingException(message, e);
        }
    }

    private void recordOutcome(final String documentName, final String documentId,
                               final String status, final String reason, final ClaimToken token) throws DocumentProcessingException {
        try {
            documentIngestionOutcomeTableService.recordOutcomeFenced(token.clientId(), documentId, status, reason, token.etag());
            LOGGER.info("event=outcome_recorded status={} documentName={} documentId={}", status, documentName, documentId);
        } catch (EtagMismatchException fenceLoss) {
            // Never convert a fence loss into a retry or a FAILED write — the reclaimer owns the outcome.
            throw fenceLoss;
        } catch (Exception e) {
            final String message = String.format("Failed to update document outcome '%s' for the documentId: '%s'", status, documentId);
            throw new DocumentProcessingException(message, e);
        }
    }

}

