package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.retrieval.service.CitationProcessor.CitationOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link CitationOutcome} degradation signal consumed by the citation guard:
 * rendered/stripped counts, block presence and the deliberate-refusal shape, across the
 * failure modes observed in evaluation runs (missing block, parse failure, entry-collapse).
 */
class CitationOutcomeTest {

    private CitationProcessor citationProcessor;

    @BeforeEach
    void setUp() {
        citationProcessor = new CitationProcessor();
    }

    @Test
    void cleanCitedAnswer_RendersAllCitations() {
        String raw = "The defendant was charged [1]. <FACT_MAP_JSON>[{\"citationId\":1,"
                + "\"documentFilename\":\"case.pdf\",\"individualPageNumbers\":\"3\",\"documentId\":\"docA\"}]</FACT_MAP_JSON>";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertTrue(outcome.jsonBlockPresent());
        assertFalse(outcome.emptyFactMap());
        assertEquals(1, outcome.inlineMarkers());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void missingFactMapBlock_StripsAllMarkersAndRendersNone() {
        String raw = "A long answer with markers [1] and [2] but the block was truncated away.";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertFalse(outcome.jsonBlockPresent());
        assertEquals(2, outcome.inlineMarkers());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(2, outcome.strippedMarkers());
        assertFalse(outcome.formattedText().contains("[1]"));
    }

    @Test
    void unparseableJson_RendersNothingAndStripsMarkers() {
        String raw = "An answer [1]. <FACT_MAP_JSON>not-json</FACT_MAP_JSON>";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertTrue(outcome.jsonBlockPresent());
        assertFalse(outcome.emptyFactMap());
        assertEquals(1, outcome.inlineMarkers());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(1, outcome.strippedMarkers());
    }

    @Test
    void entryCollapse_CountsRenderedAndStrippedSeparately() {
        // The observed gpt-4o pathology: many inline markers, one JSON entry.
        String raw = "Fact one [1]. Fact two [2]. Fact three [3]. <FACT_MAP_JSON>[{\"citationId\":1,"
                + "\"documentFilename\":\"case.pdf\",\"individualPageNumbers\":\"3\",\"documentId\":\"docA\"}]</FACT_MAP_JSON>";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertEquals(3, outcome.inlineMarkers());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(2, outcome.strippedMarkers());
    }

    @Test
    void emptyFactMapRefusal_IsTheDeliberateNoEvidenceShape() {
        String raw = "The retrieved documents do not contain this information. <FACT_MAP_JSON>[]</FACT_MAP_JSON>";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertTrue(outcome.jsonBlockPresent());
        assertTrue(outcome.emptyFactMap());
        assertEquals(0, outcome.inlineMarkers());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void mergedSameDocumentRun_CountsAsOneRenderedCitation() {
        String raw = "Arrested and charged [1][2]. <FACT_MAP_JSON>[{\"citationId\":1,"
                + "\"documentFilename\":\"case.pdf\",\"individualPageNumbers\":\"3\",\"documentId\":\"docA\"},"
                + "{\"citationId\":2,\"documentFilename\":\"case.pdf\",\"individualPageNumbers\":\"7\",\"documentId\":\"docA\"}]</FACT_MAP_JSON>";

        CitationOutcome outcome = citationProcessor.processCitations(raw);

        assertEquals(2, outcome.inlineMarkers());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void processAndFormatCitations_DelegatesToOutcomeText() {
        String raw = "The defendant was charged [1]. <FACT_MAP_JSON>[{\"citationId\":1,"
                + "\"documentFilename\":\"case.pdf\",\"individualPageNumbers\":\"3\",\"documentId\":\"docA\"}]</FACT_MAP_JSON>";

        assertEquals(citationProcessor.processCitations(raw).formattedText(),
                citationProcessor.processAndFormatCitations(raw));
    }

    @Test
    void emptyInput_YieldsEmptyOutcome() {
        CitationOutcome outcome = citationProcessor.processCitations("");
        assertEquals("", outcome.formattedText());
        assertFalse(outcome.jsonBlockPresent());
        assertEquals(0, outcome.renderedCitations());
    }
}
