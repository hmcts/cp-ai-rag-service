package uk.gov.moj.cp.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.moj.cp.harness.CitationMetrics.Compliance;
import uk.gov.moj.cp.retrieval.model.LlmResponse;

/**
 * Unit tests for {@link CitationMetrics} — the derivation of the citation/verbosity metrics that
 * every evaluation report headlines. Uses the real (deterministic) CitationProcessor via
 * {@link CitationMetrics#compute}; no environment access or network I/O is involved.
 */
class CitationMetricsTest {

    private static Compliance compute(final String raw, final String formatted) {
        return CitationMetrics.compute(new LlmResponse(raw, formatted, AnswerGenerationStatus.ANSWER_GENERATED));
    }

    /** A FACT_MAP_JSON block wrapping the given flat object literals. */
    private static String factMap(final String... objects) {
        return "\n\n<FACT_MAP_JSON>[" + String.join(",", objects) + "]</FACT_MAP_JSON>";
    }

    private static String obj(final int id, final String documentId, final String pages) {
        return "{\"citationId\":" + id + ",\"documentFilename\":\"f.pdf\",\"individualPageNumbers\":\""
                + pages + "\",\"documentId\":\"" + documentId + "\"}";
    }

    @Test
    void compute_wellFormedAnswer_reportsMarkersJsonAndPages() {
        final String raw = "The defendant assaulted the victim [1]. He later fled the scene [2]."
                + factMap(obj(1, "doc-A", "3,4"), obj(2, "doc-A", "5"));
        final Compliance c = compute(raw, "formatted ::(Source: [f.pdf], Pages 3)");

        assertTrue(c.jsonBlockPresent());
        assertEquals(2, c.rawInlineMarkers());
        assertEquals(Set.of(1, 2), c.inlineIds());
        assertEquals(2, c.jsonEntries());
        assertEquals(Set.of(1, 2), c.jsonIds());
        assertTrue(c.inlineSubsetOfJson(), "every inline id appears in the JSON");
        assertEquals(3, c.citedPages(), "pages 3,4 + 5");
        assertEquals(0, c.sameDocStackedRuns(), "markers are not adjacent");
        assertEquals(0, c.strippedMarkers());
        assertTrue(c.renderedCitations() >= 1);
        assertTrue(c.processorSubstituted());
        assertFalse(c.uncitedSubstantive(), "short answer, and it is cited");
    }

    @Test
    void compute_longAnswerWithNoCitations_isUncitedSubstantive() {
        final String raw = ("fact ".repeat(60)).trim();   // 60 prose words, no markers, no JSON
        final Compliance c = compute(raw, "");

        assertFalse(c.jsonBlockPresent());
        assertEquals(0, c.rawInlineMarkers());
        assertTrue(c.inlineIds().isEmpty());
        assertEquals(0, c.jsonEntries());
        assertEquals(0, c.citedPages());
        assertEquals(0, c.renderedCitations());
        assertTrue(c.proseWords() >= 50);
        assertTrue(c.uncitedSubstantive(), "substantive prose with zero rendered citations");
    }

    @Test
    void compute_shortRefusal_isNotUncited() {
        final Compliance c = compute("No relevant previous convictions found.", "");
        assertEquals(0, c.renderedCitations());
        assertTrue(c.proseWords() < CitationMetrics.SUBSTANTIVE_PROSE_WORDS);
        assertFalse(c.uncitedSubstantive(), "below the substantive word floor");
    }

    @Test
    void compute_adjacentMarkersSameDocument_countAsOneStackedRun() {
        final String raw = "The victim suffered harm [1][2][3] as described."
                + factMap(obj(1, "doc-A", "1"), obj(2, "doc-A", "2"), obj(3, "doc-A", "3"));
        assertEquals(1, compute(raw, "").sameDocStackedRuns());
    }

    @Test
    void compute_adjacentMarkersDifferentDocuments_doNotStack() {
        final String raw = "Two independent sources agree [1][2] on this point."
                + factMap(obj(1, "doc-A", "1"), obj(2, "doc-B", "2"));
        assertEquals(0, compute(raw, "").sameDocStackedRuns(), "cross-document adjacency is legitimate");
    }

    @Test
    void compute_inlineMarkerWithoutMatchingJson_isStrippedAndNotSubset() {
        final String raw = "A claim [5] with no matching entry here today."
                + factMap(obj(1, "doc-A", "1"));
        final Compliance c = compute(raw, "");

        assertFalse(c.inlineSubsetOfJson(), "inline id 5 is not in the JSON (only 1)");
        assertTrue(c.strippedMarkers() >= 1, "unresolved [5] is stripped");
        assertEquals(0, c.renderedCitations());
    }

    @Test
    void compute_labelledCitation_countsAsDrift() {
        final String raw = "See [Source 1] and also [2] for detail."
                + factMap(obj(1, "doc-A", "1"), obj(2, "doc-A", "2"));
        final Compliance c = compute(raw, "");

        assertEquals(1, c.rawInlineMarkers(), "only [2] is a bare [N] marker");
        assertEquals(1, c.rawDriftMarkers(), "[Source 1] is a labelled citation the bare pattern misses");
    }

    @Test
    void proseOf_stripsFactMapBlockAndBracketMarkers() {
        assertEquals("Hello world.", CitationMetrics.proseOf(
                "Hello [1] world.\n<FACT_MAP_JSON>[{\"citationId\":1}]</FACT_MAP_JSON>"));
    }

    @Test
    void proseOf_handlesNull() {
        assertEquals("", CitationMetrics.proseOf(null));
    }
}
