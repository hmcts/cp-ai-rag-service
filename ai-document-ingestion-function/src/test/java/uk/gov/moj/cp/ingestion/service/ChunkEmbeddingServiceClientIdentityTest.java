package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.EmbeddingService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingServiceClientIdentityTest {

    private static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";

    @Mock
    private EmbeddingService mockEmbeddingService;

    private ChunkEmbeddingService chunkEmbeddingService;

    @BeforeEach
    void setUp() {
        chunkEmbeddingService = new ChunkEmbeddingService(mockEmbeddingService);
    }

    @Test
    @DisplayName("Should preserve the client id when enriching chunks with embeddings")
    void shouldPreserveClientIdWhenEnrichingChunks() throws EmbeddingServiceException, DocumentProcessingException {
        final List<ChunkedEntry> mutableEntries = new ArrayList<>(List.of(createChunkedEntry(0, CLIENT_ID)));
        final List<Float> embedding = List.of(0.1f, 0.2f, 0.3f);

        when(mockEmbeddingService.embedCollectionData(anyList())).thenReturn(List.of(embedding));

        chunkEmbeddingService.enrichChunksWithEmbeddings(mutableEntries);

        final ChunkedEntry enriched = mutableEntries.get(0);
        assertEquals(embedding, enriched.chunkVector());
        assertEquals(CLIENT_ID, enriched.clientId());
    }

    @Test
    @DisplayName("Should keep a null client id null when enriching chunks with embeddings")
    void shouldKeepNullClientIdNullWhenEnrichingChunks() throws EmbeddingServiceException, DocumentProcessingException {
        final List<ChunkedEntry> mutableEntries = new ArrayList<>(List.of(createChunkedEntry(0, null)));
        final List<Float> embedding = List.of(0.1f, 0.2f, 0.3f);

        when(mockEmbeddingService.embedCollectionData(anyList())).thenReturn(List.of(embedding));

        chunkEmbeddingService.enrichChunksWithEmbeddings(mutableEntries);

        final ChunkedEntry enriched = mutableEntries.get(0);
        assertEquals(embedding, enriched.chunkVector());
        assertNull(enriched.clientId());
    }

    @Test
    @DisplayName("Should preserve every non-vector field when enriching chunks with embeddings")
    void shouldPreserveAllFieldsWhenEnrichingChunks() throws EmbeddingServiceException, DocumentProcessingException {
        final ChunkedEntry original = createChunkedEntry(3, CLIENT_ID);
        final List<ChunkedEntry> mutableEntries = new ArrayList<>(List.of(original));

        when(mockEmbeddingService.embedCollectionData(anyList())).thenReturn(List.of(List.of(0.5f)));

        chunkEmbeddingService.enrichChunksWithEmbeddings(mutableEntries);

        final ChunkedEntry enriched = mutableEntries.get(0);
        assertEquals(original.id(), enriched.id());
        assertEquals(original.documentId(), enriched.documentId());
        assertEquals(original.chunk(), enriched.chunk());
        assertEquals(original.documentFileName(), enriched.documentFileName());
        assertEquals(original.pageNumber(), enriched.pageNumber());
        assertEquals(original.chunkIndex(), enriched.chunkIndex());
        assertEquals(original.documentFileUrl(), enriched.documentFileUrl());
        assertEquals(original.customMetadata(), enriched.customMetadata());
        assertEquals(original.clientId(), enriched.clientId());
    }

    private ChunkedEntry createChunkedEntry(final int index, final String clientId) {
        return ChunkedEntry.builder()
                .id("id-" + index)
                .documentId("doc-123")
                .chunk("Chunk text " + index)
                .documentFileName("test.pdf")
                .pageNumber(1)
                .chunkIndex(index)
                .documentFileUrl("https://example.com/test.pdf")
                .customMetadata(List.of(new KeyValuePair("caseId", "case-" + index)))
                .clientId(clientId)
                .build();
    }
}
