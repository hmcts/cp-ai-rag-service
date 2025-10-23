package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkEmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkEmbeddingService.class);
    
    private final EmbeddingService embeddingService;

    public ChunkEmbeddingService() {
        // Initialize EmbeddingService with managed identity
        String embeddingServiceEndpoint = System.getenv("AZURE_EMBEDDING_SERVICE_ENDPOINT");
        String embeddingServiceDeploymentName = System.getenv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME");
        
        if (isNullOrEmpty(embeddingServiceEndpoint) || isNullOrEmpty(embeddingServiceDeploymentName)) {
            throw new IllegalStateException("Required environment variables not set: AZURE_EMBEDDING_SERVICE_ENDPOINT, AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME");
        }
        
        this.embeddingService = new EmbeddingService(embeddingServiceEndpoint,
                embeddingServiceDeploymentName);
        LOGGER.info("Initialized ChunkEmbeddingService with managed identity");
    }

    public ChunkEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public void enrichChunksWithEmbeddings(List<ChunkedEntry> chunkedEntries) {
        for (int i = 0; i < chunkedEntries.size(); i++) {
            ChunkedEntry chunkedEntry = chunkedEntries.get(i);
            if (isNullOrEmpty((chunkedEntry.chunk()))) {
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
            }
        }
    }
}
