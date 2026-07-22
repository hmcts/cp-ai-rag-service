package uk.gov.moj.cp.retrieval.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getAnswerWithChunksFilename;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getInputChunksFilename;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.transformChunkEntries;

import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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


    @Test
    @DisplayName("Prefixes input-chunks filename when clientId present")
    void prefixesInputChunksFilenameWhenClientIdPresent() {
        final UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        final String filename = getInputChunksFilename("client-1", transactionId);

        assertEquals("c=client-1/llm-input-chunks-00000000-0000-0000-0000-000000000001.json", filename);
    }

    @Test
    @DisplayName("Leaves input-chunks filename unchanged when clientId is null")
    void leavesInputChunksFilenameUnchangedWhenClientIdIsNull() {
        final UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        final String filename = getInputChunksFilename(null, transactionId);

        assertEquals("llm-input-chunks-00000000-0000-0000-0000-000000000001.json", filename);
    }

    @Test
    @DisplayName("Prefixes answer-with-chunks filename when clientId present")
    void prefixesAnswerWithChunksFilenameWhenClientIdPresent() {
        final UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        final String filename = getAnswerWithChunksFilename("client-1", transactionId);

        assertEquals("c=client-1/llm-answer-with-chunks-00000000-0000-0000-0000-000000000002.json", filename);
    }

    @Test
    @DisplayName("Leaves answer-with-chunks filename unchanged when clientId is null")
    void leavesAnswerWithChunksFilenameUnchangedWhenClientIdIsNull() {
        final UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        final String filename = getAnswerWithChunksFilename(null, transactionId);

        assertEquals("llm-answer-with-chunks-00000000-0000-0000-0000-000000000002.json", filename);
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
