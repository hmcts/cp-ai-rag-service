package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.doc;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.scopeOf;

import uk.gov.moj.cp.retrieval.model.CitationScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the same-document stacked-run merge: adjacent bare {@code [N]} markers whose
 * citationIds resolve to the SAME documentId collapse into one formatted citation with
 * the union of the entries' pages, while different-document adjacency, non-adjacent
 * reuse, and unresolved ids keep their existing behaviour. Markers of ids with no valid
 * entry are removed before run detection, so they never split a run.
 */
class CitationProcessorMergeTest {

    /** Default retrieval scope; tests that reuse an id under a different filename declare their own. */
    private static final CitationScope SCOPE = scopeOf(
            doc("doc123", "report-a.pdf", 2, 3, 4, 5, 7, 8, 10, 11, 12, 13),
            doc("docA", "report-a.pdf", 1, 2),
            doc("docB", "notes-b.pdf", 5));

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
        String raw = "The item was received and recorded [1][2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "10,11", "doc123") + "," + entry(2, "report-a.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The item was received and recorded "
                + "::(Source: [report-a.pdf], Pages 10-12|10,11,12|documentId=doc123).", result);
    }

    @Test
    void adjacentRunSeparatedBySingleSpace_IsMerged() {
        String raw = "Facts established over two days [1] [2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "3", "doc123") + "," + entry(2, "report-a.pdf", "7", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("Facts established over two days "
                + "::(Source: [report-a.pdf], Pages 3,7|3,7|documentId=doc123).", result);
    }

    @Test
    void newlineSeparatedMarkers_AreNotMerged() {
        String raw = "Fact one [1]\nFact two [2] <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "3", "doc123") + "," + entry(2, "report-a.pdf", "7", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("Fact one ::(Source: [report-a.pdf], Pages 3|3|documentId=doc123)\n"
                + "Fact two ::(Source: [report-a.pdf], Pages 7|7|documentId=doc123)", result);
    }

    @Test
    void adjacentDifferentDocuments_RemainSeparate() {
        String raw = "Both documents describe the process [1][2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "1", "docA") + "," + entry(2, "notes-b.pdf", "2", "docB")
                + "]</FACT_MAP_JSON>";
        CitationScope scope = scopeOf(doc("docA", "report-a.pdf", 1), doc("docB", "notes-b.pdf", 2));

        String result = citationProcessor.processCitations(raw, scope).formattedText();

        assertEquals("Both documents describe the process "
                + "::(Source: [report-a.pdf], Pages 1|1|documentId=docA)"
                + "::(Source: [notes-b.pdf], Pages 2|2|documentId=docB).", result);
    }

    @Test
    void mixedRun_MergesSameDocPairAndKeepsOtherDocSeparate() {
        String raw = "The sequence of events is established [1][2][3]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "1", "docA") + "," + entry(2, "report-a.pdf", "2", "docA") + ","
                + entry(3, "notes-b.pdf", "5", "docB")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The sequence of events is established "
                + "::(Source: [report-a.pdf], Pages 1,2|1,2|documentId=docA)"
                + "::(Source: [notes-b.pdf], Pages 5|5|documentId=docB).", result);
    }

    @Test
    void sameDocIdsSeparatedByOtherDocument_AreNotMerged() {
        String raw = "All three sources agree [1][3][2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "1", "docA") + "," + entry(2, "report-a.pdf", "2", "docA") + ","
                + entry(3, "notes-b.pdf", "5", "docB")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("All three sources agree "
                + "::(Source: [report-a.pdf], Pages 1|1|documentId=docA)"
                + "::(Source: [notes-b.pdf], Pages 5|5|documentId=docB)"
                + "::(Source: [report-a.pdf], Pages 2|2|documentId=docA).", result);
    }

    @Test
    void mergeIsPositional_NonAdjacentOccurrenceStillSubstitutedIndividually() {
        String raw = "It was received and recorded [1][2]. Approval was later withheld [1]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "10,11", "doc123") + "," + entry(2, "report-a.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("It was received and recorded "
                + "::(Source: [report-a.pdf], Pages 10-12|10,11,12|documentId=doc123). "
                + "Approval was later withheld ::(Source: [report-a.pdf], Pages 10,11|10,11|documentId=doc123).", result);
    }

    @Test
    void overlappingPages_AreUnionedDeduplicatedAndNumericallySorted() {
        String raw = "The timeline is set out in the appendices [1][2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "3,7", "doc123") + "," + entry(2, "report-a.pdf", "7,8,2", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The timeline is set out in the appendices "
                + "::(Source: [report-a.pdf], Pages 2,3,7,8|2,3,7,8|documentId=doc123).", result);
    }

    @Test
    void unknownIdInsideRun_IsStrippedAndRunStillMerges() {
        String raw = "The account is confirmed [1][9][2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "1", "docA") + "," + entry(2, "report-a.pdf", "2", "docA")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The account is confirmed "
                + "::(Source: [report-a.pdf], Pages 1,2|1,2|documentId=docA).", result);
    }

    @Test
    void duplicateIdRun_CollapsesToSingleCitationWithoutDoubledPages() {
        String raw = "The point is repeated for emphasis [1][1]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "4,5", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The point is repeated for emphasis "
                + "::(Source: [report-a.pdf], Pages 4,5|4,5|documentId=doc123).", result);
    }

    @Test
    void nonNumericPageTokens_AreTrimmedFromUnion() {
        String raw = "The introduction and appendix both apply [1][2]. <FACT_MAP_JSON>["
                + entry(1, "guide.pdf", "iv,2", "doc123") + "," + entry(2, "guide.pdf", "3", "doc123")
                + "]</FACT_MAP_JSON>";
        CitationScope scope = scopeOf(doc("doc123", "guide.pdf", 2, 3));

        String result = citationProcessor.processCitations(raw, scope).formattedText();

        assertEquals("The introduction and appendix both apply "
                + "::(Source: [guide.pdf], Pages 2,3|2,3|documentId=doc123).", result);
    }

    @Test
    void commaJoinedIds_AreNormalisedThenMerged() {
        String raw = "The facts are agreed [1, 2]. <FACT_MAP_JSON>["
                + entry(1, "report-a.pdf", "10,11", "doc123") + "," + entry(2, "report-a.pdf", "12", "doc123")
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The facts are agreed "
                + "::(Source: [report-a.pdf], Pages 10-12|10,11,12|documentId=doc123).", result);
    }

    @Test
    void mergedRun_RecomputesCompressedPages_IgnoringProvidedPageNumbersField() {
        String raw = "The content spans the document set [1][2]. <FACT_MAP_JSON>["
                + "{\"citationId\":1,\"documentFilename\":\"report-a.pdf\",\"pageNumbers\":\"10-12\","
                + "\"individualPageNumbers\":\"10,11,12\",\"documentId\":\"doc123\"},"
                + "{\"citationId\":2,\"documentFilename\":\"report-a.pdf\",\"pageNumbers\":\"13\","
                + "\"individualPageNumbers\":\"13\",\"documentId\":\"doc123\"}"
                + "]</FACT_MAP_JSON>";

        String result = citationProcessor.processCitations(raw, SCOPE).formattedText();

        assertEquals("The content spans the document set "
                + "::(Source: [report-a.pdf], Pages 10-13|10,11,12,13|documentId=doc123).", result);
    }
}
