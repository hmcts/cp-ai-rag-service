package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.doc;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.scopeOf;

import uk.gov.moj.cp.retrieval.model.CitationScope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-2 (GPT-5.1) hardening regression tests for {@link CitationProcessor}.
 *
 * <p>GPT-5.1 is a reasoning model: {@code max_completion_tokens} is shared between
 * the hidden reasoning trace and the visible answer + JSON block, so the response
 * can be cut off before {@code </FACT_MAP_JSON>}. The citation block is then lost
 * and the inline {@code [N]} markers cannot be resolved. These tests pin the
 * behaviour that, however the citation block is missing or incomplete, the user
 * never sees naked, unresolved brackets.
 */
class CitationProcessorGpt51Test {

    private static final CitationScope SCOPE = scopeOf(doc("abc-123", "report-a.pdf", 7));

    private static final String JSON_ONE =
            "<FACT_MAP_JSON>[{\"citationId\":1,\"documentFilename\":\"report-a.pdf\","
                    + "\"individualPageNumbers\":\"7\",\"documentId\":\"abc-123\"}]</FACT_MAP_JSON>";

    private final CitationProcessor citationProcessor = new CitationProcessor();

    @Test
    @DisplayName("Truncation: no JSON block at all — few inline [N] are stripped, not shown raw")
    void truncatedBeforeJsonBlock_stripsOrphanMarkers() {
        // Reasoning-token budget exhausted mid-answer: prose with citations, no tag emitted.
        final String raw = "The item was recorded in the register [1]. It was also listed in the index [2].";

        final String actual = citationProcessor.processAndFormatCitations(raw, SCOPE);

        assertFalse(actual.contains("[1]"), "orphan [1] must be stripped: " + actual);
        assertFalse(actual.contains("[2]"), "orphan [2] must be stripped: " + actual);
        assertTrue(actual.startsWith("The item was recorded in the register"), actual);
    }

    @Test
    @DisplayName("Truncation: unclosed JSON tag is treated as no block — markers stripped")
    void unclosedJsonTag_stripsOrphanMarkers() {
        final String raw = "The item was recorded in the register [1]. "
                + "<FACT_MAP_JSON>[{\"citationId\":1,\"documentFilename\":\"report-a.pdf\"";

        final String actual = citationProcessor.processAndFormatCitations(raw, SCOPE);

        assertFalse(actual.contains("[1]"), "orphan [1] must be stripped when tag never closes: " + actual);
    }

    @Test
    @DisplayName("Partial JSON: inline id with no JSON entry is stripped; valid id still substituted")
    void inlineIdWithoutJsonEntry_isStripped() {
        final String raw = "Claim one [1] and claim two [2]. " + JSON_ONE;

        final String actual = citationProcessor.processAndFormatCitations(raw, SCOPE);

        assertTrue(actual.contains("::(Source: [report-a.pdf]"), "valid citation 1 must render: " + actual);
        assertFalse(actual.contains("[2]"), "orphan [2] (no JSON entry) must be stripped: " + actual);
    }

    @Test
    @DisplayName("Truncation: joined ids [1, 2] with no JSON block are stripped")
    void joinedIdsNoBlock_areStripped() {
        final String raw = "Both points are confirmed [1, 2].";

        final String actual = citationProcessor.processAndFormatCitations(raw, SCOPE);

        assertFalse(actual.contains("[1"), "joined orphan markers must be stripped: " + actual);
        assertFalse(actual.contains("2]"), "joined orphan markers must be stripped: " + actual);
        assertTrue(actual.startsWith("Both points are confirmed"), actual);
    }

    @Test
    @DisplayName("No-findings answer with no brackets and no block is returned unchanged")
    void plainNoFindingsAnswer_unchanged() {
        final String raw = "The retrieved documents do not contain information about previous inspection results.";

        final String actual = citationProcessor.processAndFormatCitations(raw, SCOPE);

        assertEquals(raw, actual);
    }
}
