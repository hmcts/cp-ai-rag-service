package uk.gov.moj.cp.retrieval.service.filter;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiversificationServiceTest {

    private static final List<Float> QUERY = Arrays.asList(1.0f, 0.0f);

    private static ChunkedEntry chunk(final String id, final float x, final float y) {
        return ChunkedEntry.builder().id(id).chunkVector(Arrays.asList(x, y)).build();
    }

    private static List<String> ids(final List<ChunkedEntry> entries) {
        return entries.stream().map(ChunkedEntry::id).toList();
    }

    @Test
    @DisplayName("Returns candidates unchanged when MMR is disabled")
    void returnsCandidatesUnchangedWhenDisabled() {
        final DiversificationService disabled = new DiversificationService(0.5, 1, false);
        final List<ChunkedEntry> candidates = Arrays.asList(
                chunk("1", 1.0f, 0.0f),
                chunk("2", 1.0f, 0.0f),
                chunk("3", 0.0f, 1.0f)
        );
        final List<ChunkedEntry> result = disabled.diversify(QUERY, candidates);
        assertSame(candidates, result);
    }

    @Test
    @DisplayName("Drops a near-duplicate in favour of a diverse chunk")
    void dropsNearDuplicateInFavourOfDiverseChunk() {
        // Low lambda favours diversity. A and B are identical; C is orthogonal (unique).
        final DiversificationService service = new DiversificationService(0.3, 2, true);
        final List<ChunkedEntry> candidates = Arrays.asList(
                chunk("A", 1.0f, 0.0f),
                chunk("B", 1.0f, 0.0f),
                chunk("C", 0.0f, 1.0f)
        );
        final List<ChunkedEntry> result = service.diversify(QUERY, candidates);
        assertEquals(2, result.size());
        assertTrue(ids(result).contains("A"));
        assertTrue(ids(result).contains("C"));
    }

    @Test
    @DisplayName("High lambda favours relevance over diversity")
    void highLambdaFavoursRelevance() {
        final DiversificationService relevanceLeaning = new DiversificationService(0.9, 2, true);
        final List<ChunkedEntry> candidates = Arrays.asList(
                chunk("A", 1.0f, 0.0f),    // most relevant
                chunk("B", 0.95f, 0.05f),  // near-duplicate of A but highly relevant
                chunk("C", 0.0f, 1.0f)     // diverse but irrelevant
        );
        final List<ChunkedEntry> result = relevanceLeaning.diversify(QUERY, candidates);
        assertEquals(List.of("A", "B"), ids(result));
    }

    @Test
    @DisplayName("Low lambda favours diversity over relevance")
    void lowLambdaFavoursDiversity() {
        final DiversificationService diversityLeaning = new DiversificationService(0.1, 2, true);
        final List<ChunkedEntry> candidates = Arrays.asList(
                chunk("A", 1.0f, 0.0f),
                chunk("B", 0.95f, 0.05f),
                chunk("C", 0.0f, 1.0f)
        );
        final List<ChunkedEntry> result = diversityLeaning.diversify(QUERY, candidates);
        assertEquals(List.of("A", "C"), ids(result));
    }

    @Test
    @DisplayName("Truncates the candidate pool down to finalCount")
    void truncatesToFinalCount() {
        final DiversificationService service = new DiversificationService(0.5, 2, true);
        final List<ChunkedEntry> candidates = Arrays.asList(
                chunk("1", 1.0f, 0.0f),
                chunk("2", 0.0f, 1.0f),
                chunk("3", 1.0f, 1.0f),
                chunk("4", 0.5f, 0.5f)
        );
        final List<ChunkedEntry> result = service.diversify(QUERY, candidates);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Returns input unchanged for null or empty query vector")
    void returnsInputUnchangedForNullOrEmptyQuery() {
        final DiversificationService service = new DiversificationService(0.5, 2, true);
        final List<ChunkedEntry> candidates = List.of(chunk("1", 1.0f, 0.0f));
        assertSame(candidates, service.diversify(null, candidates));
        assertSame(candidates, service.diversify(emptyList(), candidates));
    }

    @Test
    @DisplayName("Returns input unchanged for null or empty candidates")
    void returnsInputUnchangedForNullOrEmptyCandidates() {
        final DiversificationService service = new DiversificationService(0.5, 2, true);
        assertNull(service.diversify(QUERY, null));
        assertTrue(service.diversify(QUERY, emptyList()).isEmpty());
    }

    @Test
    @DisplayName("Handles candidates with null chunk vectors without throwing")
    void handlesNullChunkVectorsWithoutThrowing() {
        final DiversificationService service = new DiversificationService(0.5, 2, true);
        final List<ChunkedEntry> candidates = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(null).build(),
                ChunkedEntry.builder().id("2").chunkVector(null).build(),
                ChunkedEntry.builder().id("3").chunkVector(null).build()
        );
        final List<ChunkedEntry> result = service.diversify(QUERY, candidates);
        assertEquals(2, result.size());
    }
}
