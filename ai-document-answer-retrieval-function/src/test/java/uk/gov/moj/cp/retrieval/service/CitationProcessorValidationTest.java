package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docA;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docB;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.scopeOf;

import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.service.CitationProcessor.CitationOutcome;
import uk.gov.moj.cp.retrieval.model.ValidationCounts;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Citation validation through {@link CitationProcessor}, with the
 * {@link CitationScopeFixtures#docA()} / {@link CitationScopeFixtures#docB()} fixtures.
 */
class CitationProcessorValidationTest {

    private static final String GARBLED_A_ID = "aaaaaaaa-0000-4000-8000-0000000000";

    private static final CitationScope SCOPE = scopeOf(docA(), docB());

    private static final String CITATION_A_2 =
            "::(Source: [report-a.pdf], Pages 2|2|documentId=" + DOC_A_ID + ")";
    private static final String CITATION_A_3 =
            "::(Source: [report-a.pdf], Pages 3|3|documentId=" + DOC_A_ID + ")";
    private static final String CITATION_A_2_3 =
            "::(Source: [report-a.pdf], Pages 2,3|2,3|documentId=" + DOC_A_ID + ")";
    private static final String CITATION_B_5 =
            "::(Source: [notes-b.pdf], Pages 5|5|documentId=" + DOC_B_ID + ")";

    private static final Pattern BARE_MARKER = Pattern.compile("\\[\\d{1,9}\\]");

    private final CitationProcessor citationProcessor = new CitationProcessor();

    private static String entry(final Object citationId, final String documentId, final String fileName,
                                final String pages) {
        final String id = citationId instanceof String ? "\"" + citationId + "\"" : String.valueOf(citationId);
        return "{\"citationId\":" + id + ",\"documentId\":\"" + documentId + "\",\"documentFilename\":\""
                + fileName + "\",\"individualPageNumbers\":\"" + pages + "\"}";
    }

    private static String block(final String... entries) {
        return " <FACT_MAP_JSON>[" + String.join(",", entries) + "]</FACT_MAP_JSON>";
    }

    private static boolean hasBareMarker(final String text) {
        return BARE_MARKER.matcher(text).find();
    }

    private CitationOutcome process(final String raw) {
        return citationProcessor.processCitations(raw, SCOPE);
    }

    // ---------- FR-15: no validation-free path ----------

    @Test
    void nullOrEmptyScope_everyEntryIsInvalidAndNothingRenders() {
        final String raw = "Fact one [1]. Fact two [2]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, DOC_B_ID, DOC_B_FILE, "5"));

        for (final CitationScope scope : new CitationScope[] {null, CitationScope.empty()}) {
            final CitationOutcome outcome = citationProcessor.processCitations(raw, scope);

            assertEquals(2, outcome.invalidEntries());
            assertEquals(0, outcome.renderedCitations());
            assertFalse(outcome.formattedText().contains("::(Source:"), outcome.formattedText());
            assertFalse(outcome.formattedText().contains("[1]"), outcome.formattedText());
            assertEquals("Fact one . Fact two .", outcome.formattedText());
        }
    }

    // ---------- FR-2 / FR-5 / FR-6: dropping invalid entries ----------

    @Test
    void garbledDocumentId_markerAndCitationAreRemoved() {
        final CitationOutcome outcome = process("Fact one [1]." + block(entry(1, GARBLED_A_ID, DOC_A_FILE, "2")));

        assertEquals("Fact one .", outcome.formattedText());
        assertEquals(1, outcome.invalidEntries());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(1, outcome.strippedMarkers());
        assertFalse(outcome.emptyFactMap());
    }

    @Test
    void missingDocumentId_rendersNoFallbackCitation() {
        final String raw = "Fact one [1]. <FACT_MAP_JSON>[{\"citationId\":1,\"documentFilename\":\"report-a.pdf\","
                + "\"individualPageNumbers\":\"2\"}]</FACT_MAP_JSON>";

        final CitationOutcome outcome = process(raw);

        assertFalse(outcome.formattedText().contains("UNKNOWN_ID"), outcome.formattedText());
        assertFalse(outcome.formattedText().contains("UNKNOWN_FILE"), outcome.formattedText());
        assertFalse(outcome.formattedText().contains("::(Source:"), outcome.formattedText());
        assertEquals(1, outcome.validation().invalidDocumentId());
    }

    @Test
    void missingFilename_rendersNoFallbackCitation() {
        final String raw = "Fact one [1]. <FACT_MAP_JSON>[{\"citationId\":1,\"documentId\":\"" + DOC_A_ID
                + "\",\"individualPageNumbers\":\"2\"}]</FACT_MAP_JSON>";

        final CitationOutcome outcome = process(raw);

        assertFalse(outcome.formattedText().contains("UNKNOWN_FILE"), outcome.formattedText());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(1, outcome.validation().invalidFileName());
    }

    // ---------- FR-7: rendering from retrieval ----------

    @Test
    void keptCitation_rendersRetrievalValuesAndRecomputedPages() {
        final String raw = "Fact one [1]. <FACT_MAP_JSON>[{\"citationId\":1,\"documentId\":\"" + DOC_A_ID
                + "\",\"documentFilename\":\"report-a.pdf\",\"pageNumbers\":\"2-7\","
                + "\"individualPageNumbers\":\"2,3,7\"}]</FACT_MAP_JSON>";

        final CitationOutcome outcome = process(raw);

        assertEquals("Fact one " + CITATION_A_2_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.trimmedPages());
        assertEquals(0, outcome.invalidEntries());
    }

    @Test
    void keptCitation_rendersTheRetrievalFilenameCasing() {
        final CitationOutcome outcome = process("Fact one [1]." + block(entry(1, DOC_A_ID, "  REPORT-A.PDF ", "3, 2")));

        assertEquals("Fact one " + CITATION_A_2_3 + ".", outcome.formattedText());
    }

    // ---------- FR-9 / FR-18: validation before merge ----------

    @Test
    void sameDocumentPair_withOneGarbledId_rendersOnlyTheValidEntry() {
        final CitationOutcome outcome = process("Claim [1][2]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, GARBLED_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(1, outcome.invalidEntries());
        assertEquals(1, outcome.strippedMarkers());
    }

    @Test
    void droppedMarkerInsideRun_doesNotPreventTheMerge() {
        final CitationOutcome outcome = process("Claim [1][2][3]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, GARBLED_A_ID, DOC_A_FILE, "2"),
                entry(3, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
        assertFalse(hasBareMarker(outcome.formattedText()), "no bare marker may remain");
    }

    @Test
    void spacedRun_withDroppedMiddleMarker_closesTheGapAndMerges() {
        final CitationOutcome outcome = process("Claim [1] [2] [3]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, DOC_A_ID, DOC_B_FILE, "2"),
                entry(3, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2_3 + ".", outcome.formattedText());
    }

    @Test
    void validEntriesForDifferentDocuments_staySeparate() {
        final CitationOutcome outcome = process("Claim [1][2]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, DOC_B_ID, DOC_B_FILE, "5")));

        assertEquals("Claim " + CITATION_A_2 + CITATION_B_5 + ".", outcome.formattedText());
        assertEquals(2, outcome.renderedCitations());
    }

    @Test
    void orphanMarkerInsideRun_isRemovedAndTheRunStillMerges() {
        final CitationOutcome outcome = process("Claim [1][9][2]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.strippedMarkers(), "the orphan [9] is stripped");
        assertEquals(0, outcome.invalidEntries(), "an orphan marker is not an invalid entry");
    }

    @Test
    void driftFormOfADroppedId_isRemovedAndCounted() {
        final CitationOutcome outcome = process("Claim [Source 2]. Other [1]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, GARBLED_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim . Other " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(1, outcome.strippedMarkers());
        assertEquals(1, outcome.inlineMarkers(), "inline markers count bare markers only");
    }

    @Test
    void markerOutsideARun_isRemovedWithoutItsSurroundingSpace() {
        final CitationOutcome outcome = process("Text [2] here [1]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, GARBLED_A_ID, DOC_A_FILE, "2")));

        assertEquals("Text  here " + CITATION_A_2 + ".", outcome.formattedText());
    }

    // ---------- FR-10: refusal vs degradation ----------

    @Test
    void allEntriesInvalid_isNotARefusal() {
        final CitationOutcome withMarkers = process("Fact one [1]. Fact two [2]."
                + block(entry(1, GARBLED_A_ID, DOC_A_FILE, "2"), entry(2, DOC_B_ID, DOC_A_FILE, "5")));
        final CitationOutcome withoutMarkers = process("Fact one. Fact two."
                + block(entry(1, GARBLED_A_ID, DOC_A_FILE, "2"), entry(2, DOC_B_ID, DOC_A_FILE, "5")));

        for (final CitationOutcome outcome : new CitationOutcome[] {withMarkers, withoutMarkers}) {
            assertTrue(outcome.jsonBlockPresent());
            assertFalse(outcome.emptyFactMap(), "the original list was not empty");
            assertEquals(0, outcome.renderedCitations());
            assertEquals(2, outcome.invalidEntries());
        }
        assertEquals(2, withMarkers.strippedMarkers());
        assertEquals(0, withoutMarkers.strippedMarkers());
    }

    @Test
    void genuineEmptyFactMap_isStillARefusal() {
        final CitationOutcome outcome = process("The retrieved documents do not say." + block());

        assertTrue(outcome.jsonBlockPresent());
        assertTrue(outcome.emptyFactMap());
        assertEquals(0, outcome.renderedCitations());
        assertEquals(0, outcome.inlineMarkers());
        assertEquals(ValidationCounts.NONE, outcome.validation());
    }

    @Test
    void noBlockOrUnparseableJson_carriesNoValidationCounts() {
        final CitationOutcome noBlock = process("Fact one [1].");
        final CitationOutcome unparseable = process("Fact one [1]. <FACT_MAP_JSON>not-json</FACT_MAP_JSON>");
        final CitationOutcome nullPayload = process("Fact one [1]. <FACT_MAP_JSON>null</FACT_MAP_JSON>");

        for (final CitationOutcome outcome : new CitationOutcome[] {noBlock, unparseable, nullPayload}) {
            assertEquals(ValidationCounts.NONE, outcome.validation());
            assertEquals(0, outcome.invalidEntries());
            assertEquals(0, outcome.trimmedPages());
            assertEquals(1, outcome.strippedMarkers());
            assertEquals("Fact one .", outcome.formattedText());
        }
    }

    @Test
    void emptyInput_carriesNoValidationCounts() {
        assertEquals(ValidationCounts.NONE, process("").validation());
        assertEquals(ValidationCounts.NONE, process(null).validation());
    }

    // ---------- FR-16: duplicates ----------

    @Test
    void duplicateCitationId_firstValidEntryRendersAtEveryMarker() {
        final CitationOutcome outcome = process("X [1]. Y [1]."
                + block(entry(1, GARBLED_A_ID, DOC_A_FILE, "2"), entry("1", DOC_A_ID, DOC_A_FILE, "2")));

        assertEquals("X " + CITATION_A_2 + ". Y " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(1, outcome.invalidEntries());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void twoValidEntriesWithTheSameId_theFirstWins() {
        final CitationOutcome outcome = process("X [1]. Y [1]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(1, DOC_B_ID, DOC_B_FILE, "5")));

        assertEquals("X " + CITATION_A_2 + ". Y " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(0, outcome.invalidEntries());
    }

    // ---------- E-13 / counts ----------

    @Test
    void invalidEntryReferencedByNoMarker_isStillCounted() {
        final CitationOutcome outcome = process("Fact [1]."
                + block(entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, GARBLED_A_ID, DOC_A_FILE, "3")));

        assertEquals("Fact " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(1, outcome.invalidEntries());
        assertEquals(0, outcome.strippedMarkers());
        assertEquals(2, outcome.validation().factMapEntries());
    }

    @Test
    void entryWithoutCitationId_isCountedAsInvalid() {
        final String raw = "Fact [1]. <FACT_MAP_JSON>[" + entry(1, DOC_A_ID, DOC_A_FILE, "3")
                + ",{\"documentId\":\"" + DOC_A_ID + "\",\"documentFilename\":\"report-a.pdf\","
                + "\"individualPageNumbers\":\"2\"}]</FACT_MAP_JSON>";

        final CitationOutcome outcome = process(raw);

        assertEquals("Fact " + CITATION_A_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.validation().invalidCitationId());
        assertEquals(1, outcome.invalidEntries());
    }

    @Test
    void cleanAnswer_hasNoStrippedOrInvalidAndEveryMarkerRenders() {
        final CitationOutcome outcome = process("A [1]. B [2]. C [1][3]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, DOC_B_ID, DOC_B_FILE, "5"),
                entry(3, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals(0, outcome.strippedMarkers());
        assertEquals(0, outcome.invalidEntries());
        assertEquals(4, outcome.inlineMarkers());
        assertEquals(3, outcome.renderedCitations(), "[1][3] merges into one citation");
        assertFalse(hasBareMarker(outcome.formattedText()), outcome.formattedText());
        assertEquals("A " + CITATION_A_2 + ". B " + CITATION_B_5 + ". C " + CITATION_A_2_3 + ".",
                outcome.formattedText());
    }

    @Test
    void validEntryTrimmedToSurvivingPages_isAcceptedWithTrimCount() {
        final CitationOutcome outcome = process("Fact [1]." + block(entry(1, DOC_A_ID, DOC_A_FILE, "2, 7, iv")));

        assertEquals("Fact " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(2, outcome.trimmedPages());
        assertEquals(0, outcome.invalidEntries());
        assertEquals(1, outcome.renderedCitations());
    }

    // ---------- robustness ----------

    @Test
    void nullJsonPayload_isAParseFailureWithoutAnException() {
        try (LogCapture logs = LogCapture.of(CitationProcessor.class)) {
            final CitationOutcome outcome = process("Fact one [1]. <FACT_MAP_JSON>null</FACT_MAP_JSON>");

            assertTrue(outcome.jsonBlockPresent());
            assertFalse(outcome.emptyFactMap());
            assertEquals(0, outcome.renderedCitations());
            assertEquals(1, outcome.strippedMarkers());
            assertEquals(ValidationCounts.NONE, outcome.validation());
            assertTrue(logs.thrown().isEmpty(), "no exception is raised or logged: " + logs.thrown());
        }
    }

    @Test
    void nonNumericCitationId_isInvalidAndBracketedTextIsLeftAsProse() {
        final String raw = "See [A note] and [a] here. Fact [1]. <FACT_MAP_JSON>["
                + "{\"citationId\":\"a\",\"documentId\":\"" + DOC_A_ID + "\",\"documentFilename\":\"report-a.pdf\","
                + "\"individualPageNumbers\":\"3\"}," + entry(1, DOC_A_ID, DOC_A_FILE, "2") + "]</FACT_MAP_JSON>";

        final CitationOutcome outcome = process(raw);

        assertEquals("See [A note] and [a] here. Fact " + CITATION_A_2 + ".", outcome.formattedText());
        assertEquals(1, outcome.validation().invalidCitationId());
        assertEquals(1, outcome.invalidEntries());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void driftFormOfADroppedIdInsideASpacedRun_isRemovedAndTheRunStillMerges() {
        final CitationOutcome outcome = process("Claim [1] [2 p.3] [3]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, GARBLED_A_ID, DOC_A_FILE, "3"),
                entry(3, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(1, outcome.strippedMarkers());
        assertEquals(1, outcome.invalidEntries());
    }

    @Test
    void driftFormOfAValidIdNextToASameDocumentMarker_mergesLikeABareMarker() {
        final CitationOutcome outcome = process("Claim [1] [Source 2]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, DOC_A_ID, DOC_A_FILE, "3")));

        assertEquals("Claim " + CITATION_A_2_3 + ".", outcome.formattedText());
        assertEquals(1, outcome.renderedCitations());
        assertEquals(0, outcome.strippedMarkers());
    }

    @Test
    void driftFormOfAValidIdNextToAnotherDocumentMarker_rendersAdjacentLikeBareMarkers() {
        final CitationOutcome outcome = process("Claim [1] [Source 2]." + block(
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, DOC_B_ID, DOC_B_FILE, "5")));

        assertEquals("Claim " + CITATION_A_2 + CITATION_B_5 + ".", outcome.formattedText());
        assertEquals(2, outcome.renderedCitations());
    }

    @Test
    void outcomeWithoutValidationCounts_defaultsToNone() {
        final CitationOutcome outcome = new CitationOutcome("text", false, false, 0, 0, 0, null);

        assertEquals(ValidationCounts.NONE, outcome.validation());
        assertEquals(0, outcome.invalidEntries());
        assertEquals(0, outcome.trimmedPages());
    }
}
