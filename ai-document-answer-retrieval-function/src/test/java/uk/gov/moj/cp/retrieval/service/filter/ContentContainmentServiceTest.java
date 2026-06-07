package uk.gov.moj.cp.retrieval.service.filter;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentContainmentServiceTest {

    private static final String SHARED =
            "The defendant attended the hearing on the first of March and the matter was adjourned to a later date.";
    private static final String CRUCIAL = " The presiding judge subsequently issued a binding restraint order.";

    private static ChunkedEntry chunk(final String id, final String text) {
        return ChunkedEntry.builder().id(id).chunk(text).build();
    }

    private static List<String> ids(final List<ChunkedEntry> entries) {
        return entries.stream().map(ChunkedEntry::id).toList();
    }

    @Test
    @DisplayName("Returns entries unchanged when containment dedup is disabled")
    void returnsEntriesUnchangedWhenDisabled() {
        final ContentContainmentService disabled = new ContentContainmentService(3, 0.95, false);
        final List<ChunkedEntry> entries = Arrays.asList(chunk("1", SHARED), chunk("2", SHARED));
        assertSame(entries, disabled.deduplicateByContainment(entries));
    }

    @Test
    @DisplayName("Collapses identical cross-file copies to a single chunk")
    void collapsesIdenticalCopies() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("fileA", SHARED),
                chunk("fileB", SHARED),
                chunk("fileC", SHARED)
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        assertEquals(List.of("fileA"), ids(result));
    }

    @Test
    @DisplayName("Keeps the superset chunk that carries the extra crucial information")
    void keepsSupersetWhenPlainCopyRankedFirst() {
        // Plain copy ranked above the "copy + crucial sentence" superset.
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("plain", SHARED),
                chunk("withCrucial", SHARED + CRUCIAL)
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        // The superset is NOT covered by the plain copy (it has unique shingles), so it survives.
        assertTrue(ids(result).contains("withCrucial"));
    }

    @Test
    @DisplayName("Drops the plain copy when the superset is ranked first")
    void dropsPlainCopyWhenSupersetRankedFirst() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("withCrucial", SHARED + CRUCIAL),
                chunk("plain", SHARED)
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        // Plain copy is fully contained in the retained superset -> dropped.
        assertEquals(List.of("withCrucial"), ids(result));
    }

    @Test
    @DisplayName("Keeps both chunks when each carries a different unique fact")
    void keepsBothWhenEachHasDistinctDelta() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("deltaA", SHARED + " A fine of two thousand pounds was imposed."),
                chunk("deltaB", SHARED + " The case was referred to the higher court for review.")
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Ignores punctuation and casing differences between copies")
    void ignoresPunctuationAndCasing() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("1", SHARED),
                chunk("2", SHARED.toUpperCase().replace(".", " !!! "))
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("Returns input unchanged for null, empty or single-element lists")
    void returnsInputUnchangedForTrivialInput() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        assertEquals(null, service.deduplicateByContainment(null));
        assertTrue(service.deduplicateByContainment(emptyList()).isEmpty());
        final List<ChunkedEntry> single = List.of(chunk("1", SHARED));
        assertSame(single, service.deduplicateByContainment(single));
    }

    @Test
    @DisplayName("Keeps blank or content-less chunks rather than treating them as covered")
    void keepsBlankChunks() {
        final ContentContainmentService service = new ContentContainmentService(3, 0.95, true);
        final List<ChunkedEntry> entries = Arrays.asList(
                chunk("1", SHARED),
                chunk("blank", "   "),
                chunk("null", null)
        );
        final List<ChunkedEntry> result = service.deduplicateByContainment(entries);
        assertEquals(3, result.size());
    }
}
