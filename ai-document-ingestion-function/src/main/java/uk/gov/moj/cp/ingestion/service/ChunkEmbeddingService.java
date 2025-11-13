package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkEmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkEmbeddingService.class);

    private static final int BATCH_SIZE = 2048;

    private final EmbeddingService embeddingService;

    public ChunkEmbeddingService() {
        // Initialize EmbeddingService with managed identity
        final String embeddingServiceEndpoint = System.getenv(AZURE_EMBEDDING_SERVICE_ENDPOINT);
        final String embeddingServiceDeploymentName = System.getenv(AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME);

        this.embeddingService = new EmbeddingService(embeddingServiceEndpoint, embeddingServiceDeploymentName);
    }

    public ChunkEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }


    public void enrichChunksWithEmbeddings(List<ChunkedEntry> chunkedEntries) {
        if (chunkedEntries == null || chunkedEntries.isEmpty()) {
            return;
        }

        List<String> chunksToEmbed = new ArrayList<>();
        List<Integer> validIndices = new ArrayList<>();

        for (int i = 0; i < chunkedEntries.size(); i++) {
            ChunkedEntry chunkedEntry = chunkedEntries.get(i);
            if (isNullOrEmpty(chunkedEntry.chunk())) {
                LOGGER.warn("Skipping chunk on page {} - empty or null text", chunkedEntry.pageNumber());
                continue;
            }
            
            String chunkText = chunkedEntry.chunk();
            int chunkSize = chunkText.length();

            LOGGER.debug("Chunk {} (page {}, index {}): {} characters",
                    i, chunkedEntry.pageNumber(), chunkedEntry.chunkIndex(), chunkSize);
            
            chunksToEmbed.add(chunkText);
            validIndices.add(i);
        }

        if (chunksToEmbed.isEmpty()) {
            LOGGER.warn("No valid chunks to embed");
            return;
        }

        LOGGER.info("Collected {} valid chunks out of {} total entries for embedding", 
                chunksToEmbed.size(), chunkedEntries.size());

        int totalProcessed = 0;

        for (int batchStart = 0; batchStart < chunksToEmbed.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, chunksToEmbed.size());
            List<String> batch = chunksToEmbed.subList(batchStart, batchEnd);
            List<Integer> batchIndices = validIndices.subList(batchStart, batchEnd);

            try {
                // Calculate batch statistics
                int batchTotalChars = batch.stream().mapToInt(String::length).sum();
                int batchAvgChars = batchTotalChars / batch.size();
                int batchEstimatedTokens = batchTotalChars / 4;
                
                LOGGER.info("Processing embedding batch {}-{} of {} ({} chunks, {} total chars, ~{} avg chars/chunk, ~{} total tokens)",
                        batchStart + 1, batchEnd, chunksToEmbed.size(), batch.size(), 
                        batchTotalChars, batchAvgChars, batchEstimatedTokens);

                List<List<Float>> embeddings = embeddingService.embedStringDataBatch(batch);

                if (embeddings.size() != batch.size()) {
                    LOGGER.error("Mismatch between number of embeddings ({}) and batch size ({})",
                            embeddings.size(), batch.size());
                    continue;
                }

                for (int i = 0; i < batchIndices.size(); i++) {
                    int originalIndex = batchIndices.get(i);
                    ChunkedEntry chunkedEntry = chunkedEntries.get(originalIndex);
                    List<Float> vector = embeddings.get(i);
                    
                    int chunkSize = chunkedEntry.chunk().length();
                    int estimatedTokens = chunkSize / 4;

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

                    chunkedEntries.set(originalIndex, enrichedEntry);
                    totalProcessed++;
                    
                    LOGGER.debug("Enriched chunk {} (page {}, index {}): {} chars, ~{} tokens, vector size {}",
                            originalIndex, chunkedEntry.pageNumber(), chunkedEntry.chunkIndex(), 
                            chunkSize, estimatedTokens, vector.size());
                }
                
                LOGGER.info("Successfully processed batch {}-{}: {} chunks enriched",
                        batchStart + 1, batchEnd, batch.size());

            } catch (EmbeddingServiceException e) {
                LOGGER.error("Failed to embed batch {}-{}: {}",
                        batchStart + 1, batchEnd, e.getMessage(), e);
            }
        }

        LOGGER.info("Successfully enriched {} chunks with embeddings", totalProcessed);
    }
}
