package uk.gov.moj.cp.retrieval.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.transformChunkEntries;

import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkUtilTest {

    @Test
    @DisplayName("Returns empty list when input is null")
    void returnsEmptyListWhenInputIsNull() {
        List<DocumentChunk> result = transformChunkEntries(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Returns empty list when input is empty")
    void returnsEmptyListWhenInputIsEmpty() {
        List<DocumentChunk> result = transformChunkEntries(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Transforms chunked entries to document chunks correctly")
    void transformsChunkedEntriesToDocumentChunksCorrectly() {
        List<ChunkedEntry> chunkedEntries = createChunkedEntries(3);

        List<DocumentChunk> result = transformChunkEntries(chunkedEntries);

        assertEquals(3, result.size());
        DocumentChunk chunk = result.getFirst();
        assertEquals(chunkedEntries.getFirst().documentId(), chunk.getDocumentId());
        assertEquals(chunkedEntries.getFirst().documentFileName(), chunk.getDocumentName());
        assertEquals(chunkedEntries.getFirst().pageNumber(), chunk.getPageNumber());
        assertEquals(chunkedEntries.getFirst().chunk(), chunk.getChunkContent());
    }

    @Test
    @DisplayName("Sets custom metadata when present in chunked entries")
    void setsCustomMetadataWhenPresentInChunkedEntries() {
        List<ChunkedEntry> chunkedEntries = createChunkedEntries(2);

        List<DocumentChunk> result = transformChunkEntries(chunkedEntries);

        assertEquals(2, result.size());
        DocumentChunk chunk = result.getFirst();
        assertEquals(2, chunk.getCustomMetadata().size());
        assertEquals("key1", chunk.getCustomMetadata().getFirst().getKey());
        assertEquals("value1", chunk.getCustomMetadata().getFirst().getValue());
    }


    private List<ChunkedEntry> createChunkedEntries(int count) {
        List<ChunkedEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(createChunkedEntry(i, "Chunk text " + i));
        }
        return entries;
    }

    private ChunkedEntry createChunkedEntry(int index, String chunkText) {
        List<KeyValuePair> pairs = List.of(new KeyValuePair("key1", "value1"), new KeyValuePair("key2", "value2"));

        return ChunkedEntry.builder()
                .id("id-" + index)
                .documentId("doc-123")
                .chunk(chunkText)
                .documentFileName("test.pdf")
                .pageNumber(1)
                .chunkIndex(index)
                .documentFileUrl("https://example.com/test.pdf")
                .customMetadata(pairs)
                .build();
    }
}
