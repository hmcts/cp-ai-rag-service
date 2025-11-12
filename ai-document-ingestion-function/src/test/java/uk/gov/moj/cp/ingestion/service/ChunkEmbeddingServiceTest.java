package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingServiceTest {

    @Mock
    private EmbeddingService mockEmbeddingService;

    private ChunkEmbeddingService chunkEmbeddingService;

    @BeforeEach
    void setUp() {
        chunkEmbeddingService = new ChunkEmbeddingService(mockEmbeddingService);
    }

    @Test
    @DisplayName("Should process multiple chunks in a single batch")
    void shouldProcessMultipleChunksInSingleBatch() throws EmbeddingServiceException {
        // given
        List<ChunkedEntry> chunkedEntries = createChunkedEntries(5);
        List<List<Float>> mockEmbeddings = createMockEmbeddings(5);

        when(mockEmbeddingService.embedStringDataBatch(anyList())).thenReturn(mockEmbeddings);

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // then
        verify(mockEmbeddingService, times(1)).embedStringDataBatch(anyList());
        
        // Verify all chunks have embeddings
        for (int i = 0; i < 5; i++) {
            assertNotNull(chunkedEntries.get(i).chunkVector());
            assertEquals(mockEmbeddings.get(i), chunkedEntries.get(i).chunkVector());
        }
    }

    @Test
    @DisplayName("Should skip empty or null chunks")
    void shouldSkipEmptyOrNullChunks() throws EmbeddingServiceException {
        // given
        List<ChunkedEntry> chunkedEntries = new ArrayList<>();
        chunkedEntries.add(createChunkedEntry(0, "Valid chunk 1"));
        chunkedEntries.add(createChunkedEntry(1, "")); // empty chunk
        chunkedEntries.add(createChunkedEntry(2, null)); // null chunk
        chunkedEntries.add(createChunkedEntry(3, "Valid chunk 2"));

        List<List<Float>> mockEmbeddings = createMockEmbeddings(2); // Only 2 valid chunks

        when(mockEmbeddingService.embedStringDataBatch(anyList())).thenReturn(mockEmbeddings);

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // then
        verify(mockEmbeddingService, times(1)).embedStringDataBatch(anyList());
        
        // Verify only valid chunks have embeddings
        assertNotNull(chunkedEntries.get(0).chunkVector());
        assertNull(chunkedEntries.get(1).chunkVector()); // empty chunk skipped
        assertNull(chunkedEntries.get(2).chunkVector()); // null chunk skipped
        assertNotNull(chunkedEntries.get(3).chunkVector());
    }

    @Test
    @DisplayName("Should process chunks in multiple batches when exceeding batch size")
    void shouldProcessChunksInMultipleBatches() throws EmbeddingServiceException {
        // given - Create 3000 chunks (more than BATCH_SIZE of 2048)
        List<ChunkedEntry> chunkedEntries = createChunkedEntries(3000);
        
        // Mock embeddings for first batch (2048 chunks)
        List<List<Float>> firstBatchEmbeddings = createMockEmbeddings(2048);
        // Mock embeddings for second batch (952 chunks)
        List<List<Float>> secondBatchEmbeddings = createMockEmbeddings(952);

        when(mockEmbeddingService.embedStringDataBatch(anyList()))
                .thenReturn(firstBatchEmbeddings)
                .thenReturn(secondBatchEmbeddings);

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // then - Should be called twice (for 2 batches)
        verify(mockEmbeddingService, times(2)).embedStringDataBatch(anyList());
        
        // Verify first batch has embeddings
        assertNotNull(chunkedEntries.get(0).chunkVector());
        assertNotNull(chunkedEntries.get(2047).chunkVector());
        
        // Verify second batch has embeddings
        assertNotNull(chunkedEntries.get(2048).chunkVector());
        assertNotNull(chunkedEntries.get(2999).chunkVector());
    }

    @Test
    @DisplayName("Should handle empty chunk list gracefully")
    void shouldHandleEmptyChunkList() throws EmbeddingServiceException {
        // given
        List<ChunkedEntry> emptyChunks = new ArrayList<>();

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(emptyChunks);

        // then
        verify(mockEmbeddingService, never()).embedStringDataBatch(anyList());
    }

    @Test
    @DisplayName("Should handle null chunk list gracefully")
    void shouldHandleNullChunkList() throws EmbeddingServiceException {
        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(null);

        // then
        verify(mockEmbeddingService, never()).embedStringDataBatch(anyList());
    }

    @Test
    @DisplayName("Should handle all chunks being empty")
    void shouldHandleAllChunksBeingEmpty() throws EmbeddingServiceException {
        // given
        List<ChunkedEntry> chunkedEntries = new ArrayList<>();
        chunkedEntries.add(createChunkedEntry(0, ""));
        chunkedEntries.add(createChunkedEntry(1, null));
        chunkedEntries.add(createChunkedEntry(2, ""));

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // then
        verify(mockEmbeddingService, never()).embedStringDataBatch(anyList());
    }

    @Test
    @DisplayName("Should handle embedding service exception gracefully")
    void shouldHandleEmbeddingServiceException() throws EmbeddingServiceException {
        // given
        List<ChunkedEntry> chunkedEntries = createChunkedEntries(3);

        when(mockEmbeddingService.embedStringDataBatch(anyList()))
                .thenThrow(new EmbeddingServiceException("API error", new Exception()));

        // when
        chunkEmbeddingService.enrichChunksWithEmbeddings(chunkedEntries);

        // then - Should not throw exception, but chunks won't have embeddings
        verify(mockEmbeddingService, times(1)).embedStringDataBatch(anyList());
        
        // Verify chunks don't have embeddings due to error
        assertNull(chunkedEntries.get(0).chunkVector());
        assertNull(chunkedEntries.get(1).chunkVector());
        assertNull(chunkedEntries.get(2).chunkVector());
    }

    private List<ChunkedEntry> createChunkedEntries(int count) {
        List<ChunkedEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(createChunkedEntry(i, "Chunk text " + i));
        }
        return entries;
    }

    private ChunkedEntry createChunkedEntry(int index, String chunkText) {
        return ChunkedEntry.builder()
                .id("id-" + index)
                .documentId("doc-123")
                .chunk(chunkText)
                .documentFileName("test.pdf")
                .pageNumber(1)
                .chunkIndex(index)
                .documentFileUrl("https://example.com/test.pdf")
                .customMetadata(List.of())
                .build();
    }

    private List<List<Float>> createMockEmbeddings(int count) {
        List<List<Float>> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add(createMockEmbedding(1536)); // Typical embedding dimension
        }
        return embeddings;
    }

    private List<Float> createMockEmbedding(int dimension) {
        List<Float> embedding = new ArrayList<>();
        for (int i = 0; i < dimension; i++) {
            embedding.add(0.1f + (i * 0.001f));
        }
        return embedding;
    }
}

