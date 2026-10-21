package uk.gov.moj.cp.retrieval.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunk;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunksOf;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docA;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docB;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.retrieval.model.CitationScope.CitableDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CitationScope}.
 */
class CitationScopeTest {

    @Test
    void from_buildsOneCitableDocumentPerRetrievedDocument() {
        final CitationScope scope = CitationScope.from(chunksOf(docA(), docB()));

        final CitableDocument a = scope.document(DOC_A_ID).orElseThrow();
        assertEquals(DOC_A_ID, a.documentId());
        assertEquals(DOC_A_FILE, a.displayedFileName());
        assertEquals(Set.of(2, 3), a.pages());

        final CitableDocument b = scope.document(DOC_B_ID).orElseThrow();
        assertEquals(DOC_B_FILE, b.displayedFileName());
        assertEquals(Set.of(5), b.pages());
        assertEquals(2, scope.documents().size());
    }

    @Test
    void from_nullOrEmptyChunkList_isTheEmptyScope() {
        assertEquals(CitationScope.empty(), CitationScope.from(null));
        assertEquals(CitationScope.empty(), CitationScope.from(List.of()));
        assertTrue(CitationScope.empty().documents().isEmpty());
    }

    @Test
    void from_nullPageNumbersContributeNoPages_documentStaysCitable() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_A_ID, DOC_A_FILE, null, null)));

        assertEquals(Set.of(2), scope.document(DOC_A_ID).orElseThrow().pages());
    }

    @Test
    void from_documentWhoseOnlyChunkHasNoPage_isOmitted() {
        final List<ChunkedEntry> chunks = new ArrayList<>(chunksOf(docA()));
        chunks.add(chunk(DOC_B_ID, DOC_B_FILE, null, null));

        final CitationScope scope = CitationScope.from(chunks);

        assertTrue(scope.document(DOC_B_ID).isEmpty(), "a document with no retrieved page is uncitable");
        assertTrue(scope.document(DOC_A_ID).isPresent());
    }

    @Test
    void from_differingMaterialNamesAcrossChunks_firstChunkWins() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "summary-a"),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "summary-a-later")));

        final CitableDocument a = scope.document(DOC_A_ID).orElseThrow();
        assertEquals("summary-a", a.displayedFileName());
        assertEquals(Set.of(2, 3), a.pages());
    }

    @Test
    void from_firstChunkHasNoDisplayedName_documentIsOmittedEvenIfALaterChunkHasOne() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, null, 2, null),
                chunk(DOC_A_ID, DOC_A_FILE, 3, null)));

        assertTrue(scope.document(DOC_A_ID).isEmpty(),
                "the prompt showed the first chunk's (null) name, so a later name must not win");
    }

    @Test
    void from_blankDisplayedName_documentIsOmitted() {
        final CitationScope scope = CitationScope.from(List.of(chunk(DOC_A_ID, "  ", 2, null)));

        assertTrue(scope.document(DOC_A_ID).isEmpty());
    }

    @Test
    void from_chunksWithNullOrBlankDocumentId_areSkipped() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(null, DOC_A_FILE, 2, null),
                chunk(" ", DOC_A_FILE, 2, null),
                chunk(DOC_B_ID, DOC_B_FILE, 5, null)));

        assertEquals(Set.of(DOC_B_ID), scope.documents().keySet());
    }

    @Test
    void from_chunksOfOneDocumentInterleavedWithAnother_areUnioned() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 3, null),
                chunk(DOC_B_ID, DOC_B_FILE, 5, null),
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_A_ID, DOC_A_FILE, 3, null)));

        assertEquals(Set.of(2, 3), scope.document(DOC_A_ID).orElseThrow().pages(), "pages are de-duplicated");
    }

    @Test
    void document_isAnExactCaseSensitiveLookup() {
        final CitationScope scope = CitationScope.from(chunksOf(docA()));

        assertTrue(scope.document(DOC_A_ID).isPresent());
        assertTrue(scope.document(DOC_A_ID.toUpperCase()).isEmpty());
        assertTrue(scope.document(" " + DOC_A_ID + " ").isEmpty());
        assertTrue(scope.document(DOC_A_ID.substring(0, DOC_A_ID.length() - 2)).isEmpty());
        assertTrue(scope.document(null).isEmpty());
    }

    @Test
    void matchesFileName_isTrimmedAndCaseInsensitive() {
        final CitableDocument a = CitationScope.from(chunksOf(docA())).document(DOC_A_ID).orElseThrow();

        assertTrue(a.matchesFileName("report-a.pdf"));
        assertTrue(a.matchesFileName("  REPORT-A.PDF "));
        assertFalse(a.matchesFileName("notes-b.pdf"));
        assertFalse(a.matchesFileName("report-a"));
        assertFalse(a.matchesFileName(null));
        assertFalse(a.matchesFileName(""));
        assertFalse(a.matchesFileName("   "));
    }

    @Test
    void scope_isImmutable() {
        final Map<String, CitableDocument> source = new HashMap<>();
        source.put(DOC_A_ID, new CitableDocument(DOC_A_ID, DOC_A_FILE, new TreeSet<>(Set.of(2, 3))));
        final CitationScope scope = new CitationScope(source);
        source.clear();

        assertEquals(1, scope.documents().size(), "defensive copy of the map");
        assertThrows(UnsupportedOperationException.class, () -> scope.documents().clear());
        final CitableDocument a = scope.document(DOC_A_ID).orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> a.pages().add(7));
    }

    @Test
    void citableDocument_copiesItsPages() {
        final TreeSet<Integer> pages = new TreeSet<>(Set.of(2));
        final CitableDocument a = new CitableDocument(DOC_A_ID, DOC_A_FILE, pages);
        pages.add(7);

        assertEquals(Set.of(2), a.pages());
    }

    @Test
    void scopesBuiltFromEqualChunkLists_areEqual() {
        final CitationScope first = CitationScope.from(chunksOf(docA(), docB()));
        final CitationScope second = CitationScope.from(chunksOf(docA(), docB()));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, CitationScope.from(chunksOf(docA())));
    }
}
