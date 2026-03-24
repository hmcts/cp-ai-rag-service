package uk.gov.moj.cp.retrieval.service;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeduplicationServiceTest {

    DeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new DeduplicationService(.95, true);
    }

    @Test
    @DisplayName("Returns all entries when all are unique")
    void returnsAllEntriesWhenAllAreUnique() {
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(Arrays.asList(1.0f, 0.0f)).build(),
                ChunkedEntry.builder().id("2").chunkVector(Arrays.asList(0.0f, 1.0f)).build()
        );
        List<ChunkedEntry> result = service.performSemanticDeduplication(entries);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Returns only one entry when all are duplicates")
    void returnsOnlyOneEntryWhenAllAreDuplicates() {
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(Arrays.asList(1.0f, 1.0f)).build(),
                ChunkedEntry.builder().id("2").chunkVector(Arrays.asList(1.0f, 1.0f)).build()
        );
        List<ChunkedEntry> result = service.performSemanticDeduplication(entries);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("Returns all entries when all are duplicates and capability not enabled")
    void returnsAllEntriesWhenAllAreDuplicatesAndCapabilityDisabled() {
        DeduplicationService deduplicationDisabledService = new DeduplicationService();
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(Arrays.asList(1.0f, 1.0f)).build(),
                ChunkedEntry.builder().id("2").chunkVector(Arrays.asList(1.0f, 1.0f)).build()
        );
        List<ChunkedEntry> result = deduplicationDisabledService.performSemanticDeduplication(entries);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Returns empty list when input is empty")
    void returnsEmptyListWhenInputIsEmpty() {
        List<ChunkedEntry> result = service.performSemanticDeduplication(emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Handles null chunk vectors gracefully")
    void handlesNullChunkVectorsGracefully() {
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(null).build(),
                ChunkedEntry.builder().id("2").chunkVector(null).build()
        );
        List<ChunkedEntry> result = service.performSemanticDeduplication(entries);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Handles vectors of different sizes as non-duplicates")
    void handlesVectorsOfDifferentSizesAsNonDuplicates() {
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(Arrays.asList(1.0f, 2.0f)).build(),
                ChunkedEntry.builder().id("2").chunkVector(Arrays.asList(1.0f)).build()
        );
        List<ChunkedEntry> result = service.performSemanticDeduplication(entries);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Returns all entries when threshold is very high")
    void returnsAllEntriesWhenThresholdIsVeryHigh() {
        DeduplicationService highThresholdService = new DeduplicationService(.99, true);
        List<ChunkedEntry> entries = Arrays.asList(
                ChunkedEntry.builder().id("1").chunkVector(Arrays.asList(1.0f, 0.0f)).build(),
                ChunkedEntry.builder().id("2").chunkVector(Arrays.asList(0.98f, 0.199f)).build()
        );
        List<ChunkedEntry> result = highThresholdService.performSemanticDeduplication(entries);
        assertEquals(2, result.size());
    }
}

