package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests covering the citation output variants GPT-4.1 is known to
 * produce. Categories:
 *
 * <ul>
 *   <li>A — placeholder bracket shape variants</li>
 *   <li>B — {@code <FACT_MAP_JSON>} tag variants</li>
 *   <li>C — JSON payload variants</li>
 *   <li>D — response structure / layout variants</li>
 * </ul>
 *
 * Each test pins one specific shape so any regression in
 * {@link CitationProcessor} surfaces immediately at build time.
 */
class CitationProcessorGpt41Test {

    private final CitationProcessor citationProcessor = new CitationProcessor();

    private static final String CITATION_FORMATTED =
            "::(Source: [case.pdf], Pages 7|7|documentId=abc-123)";

    private static final String JSON_ONE =
            "<FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                    + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\","
                    + "\"documentId\":\"abc-123\"}]</FACT_MAP_JSON>";

    private static final String JSON_TWO =
            "<FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"a.pdf\","
                    + "\"pageNumbers\":\"1\",\"individualPageNumbers\":\"1\",\"documentId\":\"a-id\"},"
                    + "{\"citationId\":\"2\",\"documentFilename\":\"b.pdf\","
                    + "\"pageNumbers\":\"2\",\"individualPageNumbers\":\"2\",\"documentId\":\"b-id\"}]</FACT_MAP_JSON>";

    // ---------- A. Placeholder bracket shapes ----------

    @Test @DisplayName("A1 [1] bare bracket")
    void a1_bareBracket() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [1]. " + JSON_ONE));
    }

    @Test @DisplayName("A2 [1:7] colon-suffixed page hint")
    void a2_colonSuffix() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [1:7]. " + JSON_ONE));
    }

    @Test @DisplayName("A3 [1, page 7] full-word page suffix")
    void a3_fullWordPage() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [1, page 7]. " + JSON_ONE));
    }

    @Test @DisplayName("A4 **[1]** markdown bold around placeholder")
    void a4_boldMarkdown() {
        assertEquals("The defendant was charged **" + CITATION_FORMATTED + "**.",
                citationProcessor.processAndFormatCitations("The defendant was charged **[1]**. " + JSON_ONE));
    }

    @Test @DisplayName("A5 [^1] footnote-style placeholder")
    void a5_footnoteStyle() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [^1]. " + JSON_ONE));
    }

    @Test @DisplayName("A6 [1, 2] comma-joined ids — both substituted")
    void a6_commaJoinedIds() {
        final String actual = citationProcessor.processAndFormatCitations(
                "Both Acts criminalise this [1, 2]. " + JSON_TWO);

        assertTrue(actual.contains("a.pdf"), "citation 1 missing: " + actual);
        assertTrue(actual.contains("b.pdf"), "citation 2 missing: " + actual);
    }

    @Test @DisplayName("A7 [1; 2] semicolon-joined ids — both substituted")
    void a7_semicolonJoinedIds() {
        final String actual = citationProcessor.processAndFormatCitations(
                "Both Acts criminalise this [1; 2]. " + JSON_TWO);

        assertTrue(actual.contains("a.pdf"), "citation 1 missing: " + actual);
        assertTrue(actual.contains("b.pdf"), "citation 2 missing: " + actual);
    }

    @Test @DisplayName("A8 [ 1 ] whitespace inside bracket")
    void a8_whitespaceInsideBracket() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [ 1 ]. " + JSON_ONE));
    }

    @Test @DisplayName("A9 [citation 1] lowercase label")
    void a9_lowercaseLabel() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [citation 1]. " + JSON_ONE));
    }

    @Test @DisplayName("A10 [Citation: 1] label with colon separator")
    void a10_labelWithColon() {
        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations("The defendant was charged [Citation: 1]. " + JSON_ONE));
    }

    @Test @DisplayName("A11 (1) parens must NOT be treated as a citation")
    void a11_parensNotCitation() {
        // The system prompt reserves parens for in-prose list enumeration.
        final String raw = "The defendant was charged (1). " + JSON_ONE;
        assertEquals("The defendant was charged (1).",
                citationProcessor.processAndFormatCitations(raw));
    }

    // ---------- B. FACT_MAP_JSON tag variants ----------

    @Test @DisplayName("B1 ```xml fence wrapping the tag")
    void b1_codeFenceAroundTag() {
        final String raw = "The defendant was charged [1].\n```xml\n" + JSON_ONE + "\n```";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains(CITATION_FORMATTED), "expected substituted citation, got: " + actual);
    }

    @Test @DisplayName("B2 ```json fence INSIDE the tag (around the JSON payload)")
    void b2_codeFenceInsideTag() {
        final String raw = "The defendant was charged [1].\n<FACT_MAP_JSON>\n```json\n"
                + "[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\",\"documentId\":\"abc-123\"}]"
                + "\n```\n</FACT_MAP_JSON>";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains(CITATION_FORMATTED), "expected substituted citation, got: " + actual);
    }

    @Test @DisplayName("B3 lowercase <fact_map_json> tag")
    void b3_lowercaseTag() {
        final String raw = "The defendant was charged [1]. "
                + "<fact_map_json>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\",\"documentId\":\"abc-123\"}]</fact_map_json>";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains(CITATION_FORMATTED), "expected substituted citation, got: " + actual);
    }

    @Test @DisplayName("B4 <FACT_MAP_JSON > tag with internal whitespace")
    void b4_tagWithWhitespace() {
        final String raw = "The defendant was charged [1]. "
                + "<FACT_MAP_JSON >[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\",\"documentId\":\"abc-123\"}]</FACT_MAP_JSON >";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains(CITATION_FORMATTED), "expected substituted citation, got: " + actual);
    }

    // ---------- C. JSON payload variants ----------

    @Test @DisplayName("C1 citationId as a JSON number rather than a string")
    void c1_citationIdAsJsonNumber() {
        final String raw = "The defendant was charged [1]. "
                + "<FACT_MAP_JSON>[{\"citationId\":1,\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\",\"documentId\":\"abc-123\"}]</FACT_MAP_JSON>";

        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations(raw));
    }

    @Test @DisplayName("C2 individualPageNumbers as a JSON array rather than a CSV string")
    void c2_pageNumbersAsArray() {
        // pageNumbers omitted to force the processor to compress the array itself.
        final String raw = "The defendant was charged [1]. "
                + "<FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"individualPageNumbers\":[7,8,9],\"documentId\":\"abc-123\"}]</FACT_MAP_JSON>";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains("Pages 7-9|7,8,9|"),
                "expected derived compressed range from array, got: " + actual);
    }

    @Test @DisplayName("C3 missing documentId degrades to UNKNOWN_ID rather than throwing")
    void c3_missingDocumentId() {
        final String raw = "The defendant was charged [1]. "
                + "<FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"case.pdf\","
                + "\"pageNumbers\":\"7\",\"individualPageNumbers\":\"7\"}]</FACT_MAP_JSON>";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains("documentId=UNKNOWN_ID"),
                "expected UNKNOWN_ID fallback, got: " + actual);
    }

    // ---------- D. Response structure / layout ----------

    @Test @DisplayName("D1 citation tag emitted BEFORE the answer body — answer preserved")
    void d1_jsonBeforeAnswer() {
        final String raw = JSON_ONE + "\n\nThe defendant was charged [1].";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains("The defendant was charged"), "answer body lost: " + actual);
        assertTrue(actual.contains(CITATION_FORMATTED), "citation not substituted: " + actual);
    }

    @Test @DisplayName("D2 prose AFTER the citation tag is preserved")
    void d2_proseAfterJsonTag() {
        final String raw = "The defendant was charged [1]. " + JSON_ONE
                + "\n\nLet me know if you need more.";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains(CITATION_FORMATTED), "citation not substituted: " + actual);
        assertTrue(actual.contains("Let me know if you need more."), "trailing prose lost: " + actual);
    }

    @Test @DisplayName("D3 citation tag in the MIDDLE — prose on both sides preserved")
    void d3_jsonInMiddle() {
        final String raw = "The defendant was charged [1]. " + JSON_ONE
                + " Additional context here [1].";

        final String actual = citationProcessor.processAndFormatCitations(raw);
        assertTrue(actual.contains("The defendant was charged"), "leading prose lost: " + actual);
        assertTrue(actual.contains("Additional context here"), "trailing prose lost: " + actual);

        final int substitutions = actual.split("::\\(Source", -1).length - 1;
        assertEquals(2, substitutions,
                "expected 2 substitutions, got " + substitutions + " in: " + actual);
    }

    @Test @DisplayName("D4 CRLF line endings are tolerated")
    void d4_crlfLineEndings() {
        final String raw = "The defendant was charged [1].\r\n" + JSON_ONE;

        assertEquals("The defendant was charged " + CITATION_FORMATTED + ".",
                citationProcessor.processAndFormatCitations(raw));
    }
}
