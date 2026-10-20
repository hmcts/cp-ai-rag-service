package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Contract of the sentinel holder: distinct sentinels per registration, counting by occurrence,
 * verbatim expansion (no regex replacement syntax), and a model text that cannot forge one.
 */
class RenderedCitationsTest {

    @Test
    void register_returnsADistinctSentinelPerCitation_thatCarriesNoMarkerSyntax() {
        final RenderedCitations rendered = new RenderedCitations();

        final String first = rendered.register("first");
        final String second = rendered.register("first");

        assertNotEquals(first, second);
        assertFalse(first.contains("[") || first.contains("]"));
    }

    @Test
    void expand_replacesEachSentinelWithItsCitation() {
        final RenderedCitations rendered = new RenderedCitations();
        final String a = rendered.register("::(A)");
        final String b = rendered.register("::(B)");

        assertEquals("x ::(A) y ::(B) z ::(A)", rendered.expand("x " + a + " y " + b + " z " + a));
    }

    @Test
    void expand_insertsReplacementSyntaxVerbatim() {
        final RenderedCitations rendered = new RenderedCitations();
        final String sentinel = rendered.register("::(Source: [price $1 \\ $0 report.pdf])");

        assertEquals("see ::(Source: [price $1 \\ $0 report.pdf])", rendered.expand("see " + sentinel));
    }

    @Test
    void expand_leavesTextWithoutSentinelsUnchanged() {
        final RenderedCitations rendered = new RenderedCitations();
        rendered.register("::(A)");

        assertEquals("plain [1] text", rendered.expand("plain [1] text"));
    }

    @Test
    void count_countsEveryOccurrence_soACitationUsedTwiceCountsTwice() {
        final RenderedCitations rendered = new RenderedCitations();
        final String a = rendered.register("::(A)");
        final String b = rendered.register("::(B)");

        assertEquals(3, rendered.count(a + " " + b + " " + a));
        assertEquals(0, rendered.count("no citations here"));
    }

    @Test
    void withoutSentinelDelimiter_stripsTheDelimiter_soModelTextCannotForgeASentinel() {
        final RenderedCitations rendered = new RenderedCitations();
        final String sentinel = rendered.register("::(A)");

        final String cleaned = RenderedCitations.withoutSentinelDelimiter("forged " + sentinel);

        assertEquals(0, rendered.count(cleaned));
        assertEquals(cleaned, rendered.expand(cleaned));
    }

    @Test
    void withoutSentinelDelimiter_returnsTheSameInstanceWhenThereIsNothingToStrip() {
        final String text = "ordinary answer text [1]";

        assertSame(text, RenderedCitations.withoutSentinelDelimiter(text));
    }
}
