package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the four citation drift failure modes the post-processor
 * must tolerate. Each test pins one specific LLM output shape so a future
 * change to {@link CitationProcessor} cannot silently regress it.
 *
 * <p>The drift modes are documented in {@code docs/prompt-evaluation-report.md}
 * and were the root cause of citation rendering succeeding only ~1 in 10 times
 * in production.
 */
class CitationProcessorDriftTest {

    private final CitationProcessor citationProcessor = new CitationProcessor();

    private static final String CITATION_FORMATTED =
            "::(Source: [case.pdf], Pages 7|7|documentId=abc-123)";

    private static final String CITATION_JSON_BLOCK =
            "<FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                    + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\","
                    + "\"documentId\":\"abc-123\"}]</FACT_MAP_JSON>";

    @Test
    @DisplayName("Drift 1: [1 p.7] page-suffix drift is still substituted")
    void pageSuffixInBracket_isSubstituted() {
        final String raw = "The defendant was charged with assault [1 p.7]. " + CITATION_JSON_BLOCK;
        final String expected = "The defendant was charged with assault " + CITATION_FORMATTED + ".";

        assertEquals(expected, citationProcessor.processAndFormatCitations(raw));
    }

    @Test
    @DisplayName("Drift 2: [Source 1] label-padded drift is still substituted")
    void labelPaddedBracket_isSubstituted() {
        final String raw = "The defendant was charged with assault [Source 1]. " + CITATION_JSON_BLOCK;
        final String expected = "The defendant was charged with assault " + CITATION_FORMATTED + ".";

        assertEquals(expected, citationProcessor.processAndFormatCitations(raw));
    }

    @Test
    @DisplayName("Drift 3: echoed example <FACT_MAP_JSON> does not eat the real answer")
    void echoedExampleJsonBlock_doesNotDiscardRealAnswer() {
        final String raw = "For example: <FACT_MAP_JSON>[]</FACT_MAP_JSON>\n\n"
                + "The real answer cites [1]. " + CITATION_JSON_BLOCK;
        final String expected = "For example: <FACT_MAP_JSON>[]</FACT_MAP_JSON>\n\n"
                + "The real answer cites " + CITATION_FORMATTED + ".";

        assertEquals(expected, citationProcessor.processAndFormatCitations(raw));
    }

    @Test
    @DisplayName("Drift 4: catastrophic counter-loop output degrades gracefully (markers stripped)")
    void catastrophicCounterLoop_isStrippedGracefully() {
        final StringBuilder runaway = new StringBuilder("Some narrative ");
        for (int i = 1; i <= 200; i++) {
            runaway.append('[').append(i).append(']');
        }
        // Truncation at maxTokens leaves the JSON without a closing tag
        runaway.append(". <FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"1\",\"individualPageNumbers\":\"1\",\"documentId\":\"abc\"}");

        final String actual = citationProcessor.processAndFormatCitations(runaway.toString());

        final long bracketCount = actual.chars().filter(c -> c == '[').count();
        assertTrue(bracketCount < 100,
                "Output must not pass >100 raw [N] markers through to the user; got " + bracketCount);
        assertFalse(actual.contains("[200]"),
                "Runaway counter markers must be stripped from the user-visible output");
    }
}
