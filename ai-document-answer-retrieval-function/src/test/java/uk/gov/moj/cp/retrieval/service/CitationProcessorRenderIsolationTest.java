package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunk;

import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.service.CitationProcessor.CitationOutcome;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins that a filename looking like a marker or a citation is rendered verbatim and never
 * re-processed (see {@link RenderedCitations}).
 */
class CitationProcessorRenderIsolationTest {

    private final CitationProcessor citationProcessor = new CitationProcessor();

    private static String entry(final int citationId, final String documentId, final String fileName,
                                final String pages) {
        return "{\"citationId\":" + citationId + ",\"documentId\":\"" + documentId + "\",\"documentFilename\":\""
                + fileName + "\",\"individualPageNumbers\":\"" + pages + "\"}";
    }

    private static String block(final String... entries) {
        return " <FACT_MAP_JSON>[" + String.join(",", entries) + "]</FACT_MAP_JSON>";
    }

    private static String citation(final String fileName, final String pages, final String documentId) {
        return "::(Source: [" + fileName + "], Pages " + pages + "|" + pages + "|documentId=" + documentId + ")";
    }

    @Test
    void displayedNameContainingReplacementSyntax_isRenderedVerbatim() {
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, DOC_A_FILE, 2, "price $1 report.pdf")));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact [1]." + block(entry(1, DOC_A_ID, "price $1 report.pdf", "2")), scope);

        assertEquals("Fact " + citation("price $1 report.pdf", "2", DOC_A_ID) + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
    }

    @Test
    void numericDisplayedName_isNotStrippedAsAnUnresolvedMarker() {
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, DOC_A_FILE, 2, "12345")));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact [1]." + block(entry(1, DOC_A_ID, "12345", "2")), scope);

        assertEquals("Fact " + citation("12345", "2", DOC_A_ID) + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void displayedNameStartingWithAnotherCitationId_isNotSubstitutedAgain() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_B_ID, "2 notes.pdf", 5, null)));

        final CitationOutcome outcome = citationProcessor.processCitations("X [1]. Y [2]."
                + block(entry(1, DOC_B_ID, "2 notes.pdf", "5"), entry(2, DOC_A_ID, DOC_A_FILE, "2")), scope);

        assertEquals("X " + citation("2 notes.pdf", "5", DOC_B_ID) + ". Y " + citation(DOC_A_FILE, "2", DOC_A_ID) + ".",
                outcome.formattedText());
        assertEquals(2, outcome.renderedCitations());
    }

    @Test
    void displayedNameContainingABareMarker_isRenderedVerbatim() {
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, "report [1].pdf", 2, null)));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact [1]. Other [1]." + block(entry(1, DOC_A_ID, "report [1].pdf", "2")), scope);

        final String rendered = citation("report [1].pdf", "2", DOC_A_ID);
        assertEquals("Fact " + rendered + ". Other " + rendered + ".", outcome.formattedText());
        assertEquals(2, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void displayedNameContainingTheRenderedPrefix_doesNotInflateTheRenderedCount() {
        final String trickyName = "copy ::(Source: [x.pdf";
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, trickyName, 2, null)));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact [1]." + block(entry(1, DOC_A_ID, trickyName, "2")), scope);

        assertEquals("Fact " + citation(trickyName, "2", DOC_A_ID) + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
    }

    @Test
    void mergedCitationWithNumericDisplayedName_survivesTheMarkerPasses() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "777"),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "777")));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact [1][2]." + block(entry(1, DOC_A_ID, "777", "2"), entry(2, DOC_A_ID, "777", "3")), scope);

        assertEquals("Fact " + citation("777", "2,3", DOC_A_ID) + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
    }

    @Test
    void sentinelDelimiterInTheModelText_cannotForgeARenderedCitation() {
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, DOC_A_FILE, 2, null)));

        final CitationOutcome outcome = citationProcessor.processCitations(
                "Fact \uE000CIT0\uE000 here [1]." + block(entry(1, DOC_A_ID, DOC_A_FILE, "2")), scope);

        assertFalse(outcome.formattedText().contains("\uE000"), outcome.formattedText());
        assertEquals("Fact CIT0 here " + citation(DOC_A_FILE, "2", DOC_A_ID) + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
    }
}
