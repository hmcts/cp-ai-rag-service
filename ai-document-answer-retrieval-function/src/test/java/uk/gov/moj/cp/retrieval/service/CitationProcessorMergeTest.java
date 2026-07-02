package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the same-document stacked-run merge: adjacent bare {@code [N]} markers whose
 * citationIds resolve to the SAME documentId collapse into one formatted citation with
 * the union of the entries' pages, while different-document adjacency, non-adjacent
 * reuse, and unresolved ids keep their existing behaviour.
 */
class CitationProcessorMergeTest {

    private CitationProcessor citationProcessor;

    @BeforeEach
    void setUp() {
        citationProcessor = new CitationProcessor();
    }

    private static String entry(final int id, final String file, final String pages, final String docId) {
        return "{\"citationId\":" + id + ",\"documentFilename\":\"" + file
                + "\",\"individualPageNumbers\":\"" + pages + "\",\"documentId\":\"" + docId + "\"}";
    }

    @Test
    void adjacentSameDocumentRun_IsMergedIntoSingleCitation() {
        String raw = "The defendant was arrested and charged [1][2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "10,11", "doc123") + "," + entry(2, "case.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The defendant was arrested and charged "
                + "::(Source: [case.pdf], Pages 10-12|10,11,12|documentId=doc123).", result);
    }

    @Test
    void adjacentRunSeparatedBySingleSpace_IsMerged() {
        String raw = "Facts established over two days [1] [2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "3", "doc123") + "," + entry(2, "case.pdf", "7", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("Facts established over two days "
                + "::(Source: [case.pdf], Pages 3,7|3,7|documentId=doc123).", result);
    }

    @Test
    void newlineSeparatedMarkers_AreNotMerged() {
        String raw = "Fact one [1]\nFact two [2] <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "3", "doc123") + "," + entry(2, "case.pdf", "7", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("Fact one ::(Source: [case.pdf], Pages 3|3|documentId=doc123)\n"
                + "Fact two ::(Source: [case.pdf], Pages 7|7|documentId=doc123)", result);
    }

    @Test
    void adjacentDifferentDocuments_RemainSeparate() {
        String raw = "Both Acts criminalise the conduct [1][2]. <FACT_MAP_JSON>["
                + entry(1, "theft-act.pdf", "1", "docA") + "," + entry(2, "fraud-act.pdf", "2", "docB")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("Both Acts criminalise the conduct "
                + "::(Source: [theft-act.pdf], Pages 1|1|documentId=docA)"
                + "::(Source: [fraud-act.pdf], Pages 2|2|documentId=docB).", result);
    }

    @Test
    void mixedRun_MergesSameDocPairAndKeepsOtherDocSeparate() {
        String raw = "The sequence of events is established [1][2][3]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "1", "docA") + "," + entry(2, "case.pdf", "2", "docA") + ","
                + entry(3, "witness.pdf", "5", "docB")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The sequence of events is established "
                + "::(Source: [case.pdf], Pages 1,2|1,2|documentId=docA)"
                + "::(Source: [witness.pdf], Pages 5|5|documentId=docB).", result);
    }

    @Test
    void sameDocIdsSeparatedByOtherDocument_AreNotMerged() {
        String raw = "All three sources agree [1][3][2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "1", "docA") + "," + entry(2, "case.pdf", "2", "docA") + ","
                + entry(3, "witness.pdf", "5", "docB")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("All three sources agree "
                + "::(Source: [case.pdf], Pages 1|1|documentId=docA)"
                + "::(Source: [witness.pdf], Pages 5|5|documentId=docB)"
                + "::(Source: [case.pdf], Pages 2|2|documentId=docA).", result);
    }

    @Test
    void mergeIsPositional_NonAdjacentOccurrenceStillSubstitutedIndividually() {
        String raw = "He was arrested and charged [1][2]. Bail was later refused [1]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "10,11", "doc123") + "," + entry(2, "case.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("He was arrested and charged "
                + "::(Source: [case.pdf], Pages 10-12|10,11,12|documentId=doc123). "
                + "Bail was later refused ::(Source: [case.pdf], Pages 10,11|10,11|documentId=doc123).", result);
    }

    @Test
    void overlappingPages_AreUnionedDeduplicatedAndNumericallySorted() {
        String raw = "The chronology is set out in the exhibits [1][2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "3,7", "doc123") + "," + entry(2, "case.pdf", "7,8,2", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The chronology is set out in the exhibits "
                + "::(Source: [case.pdf], Pages 2,3,7,8|2,3,7,8|documentId=doc123).", result);
    }

    @Test
    void unknownIdInsideRun_BreaksMergeAndIsStripped() {
        String raw = "The account is corroborated [1][9][2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "1", "docA") + "," + entry(2, "case.pdf", "2", "docA")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The account is corroborated "
                + "::(Source: [case.pdf], Pages 1|1|documentId=docA)"
                + "::(Source: [case.pdf], Pages 2|2|documentId=docA).", result);
    }

    @Test
    void duplicateIdRun_CollapsesToSingleCitationWithoutDoubledPages() {
        String raw = "The point is repeated for emphasis [1][1]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "4,5", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The point is repeated for emphasis "
                + "::(Source: [case.pdf], Pages 4,5|4,5|documentId=doc123).", result);
    }

    @Test
    void nonNumericPageTokens_AreToleratedInUnion() {
        String raw = "The preamble and schedule both apply [1][2]. <FACT_MAP_JSON>["
                + entry(1, "act.pdf", "iv,2", "doc123") + "," + entry(2, "act.pdf", "3", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The preamble and schedule both apply "
                + "::(Source: [act.pdf], Pages 2,3,iv|2,3,iv|documentId=doc123).", result);
    }

    @Test
    void commaJoinedIds_AreNormalisedThenMerged() {
        String raw = "The facts are agreed [1, 2]. <FACT_MAP_JSON>["
                + entry(1, "case.pdf", "10,11", "doc123") + "," + entry(2, "case.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The facts are agreed "
                + "::(Source: [case.pdf], Pages 10-12|10,11,12|documentId=doc123).", result);
    }

    @Test
    void mergedRun_RecomputesCompressedPages_IgnoringProvidedPageNumbersField() {
        String raw = "The evidence spans the bundle [1][2]. <FACT_MAP_JSON>["
                + "{\"citationId\":1,\"documentFilename\":\"case.pdf\",\"pageNumbers\":\"10-12\","
                + "\"individualPageNumbers\":\"10,11,12\",\"documentId\":\"doc123\"},"
                + "{\"citationId\":2,\"documentFilename\":\"case.pdf\",\"pageNumbers\":\"13\","
                + "\"individualPageNumbers\":\"13\",\"documentId\":\"doc123\"}"
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(raw);

        assertEquals("The evidence spans the bundle "
                + "::(Source: [case.pdf], Pages 10-13|10,11,12,13|documentId=doc123).", result);
    }
}
