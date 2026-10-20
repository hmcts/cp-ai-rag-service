package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunk;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.doc;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docA;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docB;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.scopeOf;

import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ResolvedCitation;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ValidationResult;
import uk.gov.moj.cp.retrieval.service.CitationProcessor.ValidationCounts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CitationEntryValidator}: each {@code FACT_MAP_JSON} entry is checked
 * against the {@link CitationScope} the model was shown. Fixtures: document A
 * ({@code report-a.pdf}, pages {2, 3}) and document B ({@code notes-b.pdf}, page {5}).
 */
class CitationEntryValidatorTest {

    private static final String GARBLED_A_ID = "aaaaaaaa-0000-4000-8000-0000000000";

    private static final CitationScope SCOPE = scopeOf(docA(), docB());

    /** Parses a JSON array literal exactly as the processor does, so number/string types are real. */
    private static List<Map<String, Object>> entries(final String jsonArray) {
        try {
            return getObjectMapper().readValue(jsonArray, new TypeReference<>() { });
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String entry(final Object citationId, final String documentId, final String fileName,
                                final String pagesJson) {
        final List<String> fields = new ArrayList<>();
        if (citationId != null) {
            fields.add("\"citationId\":" + (citationId instanceof String ? "\"" + citationId + "\"" : citationId));
        }
        if (documentId != null) {
            fields.add("\"documentId\":\"" + documentId + "\"");
        }
        if (fileName != null) {
            fields.add("\"documentFilename\":\"" + fileName + "\"");
        }
        if (pagesJson != null) {
            fields.add("\"individualPageNumbers\":" + pagesJson);
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String array(final String... objects) {
        return "[" + String.join(",", objects) + "]";
    }

    private static ValidationResult validate(final CitationScope scope, final String... objects) {
        return CitationEntryValidator.validate(entries(array(objects)), scope);
    }

    private static ValidationResult validate(final String... objects) {
        return validate(SCOPE, objects);
    }

    @Test
    void validEntry_resolvesToTheRetrievedDocumentAndItsPages() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"2,3\""));

        final ResolvedCitation resolved = result.resolvedById().get("1");
        assertEquals("1", resolved.citationId());
        assertEquals(DOC_A_ID, resolved.document().documentId());
        assertEquals(DOC_A_FILE, resolved.document().displayedFileName());
        assertEquals(Set.of(2, 3), resolved.pages());
        assertEquals(0, resolved.trimmedPages());
        assertTrue(result.droppedIds().isEmpty());
        assertEquals(new ValidationCounts(1, 0, 0, 0, 0, 0), result.counts());
    }

    @Test
    void garbledDocumentId_isDropped() {
        final ValidationResult result = validate(entry(1, GARBLED_A_ID, DOC_A_FILE, "\"2\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(Set.of("1"), result.droppedIds());
        assertEquals(1, result.counts().invalidEntries());
        assertEquals(1, result.counts().invalidDocumentId());
    }

    @Test
    void documentIdDifferingOnlyByCaseOrWhitespace_isDropped() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID.toUpperCase(), DOC_A_FILE, "\"2\""),
                entry(2, " " + DOC_A_ID + " ", DOC_A_FILE, "\"2\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(2, result.counts().invalidDocumentId());
    }

    @Test
    void missingNullOrNonStringDocumentId_isDroppedWithoutException() {
        final ValidationResult result = validate(
                entry(1, null, DOC_A_FILE, "\"2\""),
                "{\"citationId\":2,\"documentId\":null,\"documentFilename\":\"report-a.pdf\",\"individualPageNumbers\":\"2\"}",
                "{\"citationId\":3,\"documentId\":42,\"documentFilename\":\"report-a.pdf\",\"individualPageNumbers\":\"2\"}",
                "{\"citationId\":4,\"documentId\":[\"" + DOC_A_ID + "\"],\"documentFilename\":\"report-a.pdf\",\"individualPageNumbers\":\"2\"}");

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(4, result.counts().invalidDocumentId());
        assertEquals(Set.of("1", "2", "3", "4"), result.droppedIds());
    }

    @Test
    void filenameIsTrimmedAndCaseInsensitive() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, "  REPORT-A.PDF ", "\"2\""));

        assertEquals(DOC_A_FILE, result.resolvedById().get("1").document().displayedFileName());
        assertEquals(0, result.counts().invalidEntries());
    }

    @Test
    void anotherRetrievedDocumentsFilename_isDropped() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_B_FILE, "\"2\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(1, result.counts().invalidFileName());
    }

    @Test
    void missingOrNonStringFilename_isDropped() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID, null, "\"2\""),
                "{\"citationId\":2,\"documentId\":\"" + DOC_A_ID + "\",\"documentFilename\":7,\"individualPageNumbers\":\"2\"}");

        assertEquals(2, result.counts().invalidFileName());
    }

    @Test
    void materialName_isTheOnlyAcceptedFilename() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "summary-a"),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "summary-a")));

        final ValidationResult result = validate(scope,
                entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry(2, DOC_A_ID, "summary-a", "\"3\""));

        assertEquals(Set.of("2"), result.resolvedById().keySet());
        assertEquals("summary-a", result.resolvedById().get("2").document().displayedFileName());
        assertEquals(1, result.counts().invalidFileName());
    }

    @Test
    void sameFilenameOnTwoDocuments_pagesAreValidatedAgainstTheEntrysOwnDocument() {
        final CitationScope scope = scopeOf(doc(DOC_A_ID, DOC_A_FILE, 2, 3), doc(DOC_B_ID, DOC_A_FILE, 5));

        final ValidationResult result = validate(scope, entry(1, DOC_A_ID, DOC_A_FILE, "\"2,5\""));

        final ResolvedCitation resolved = result.resolvedById().get("1");
        assertEquals(DOC_A_ID, resolved.document().documentId());
        assertEquals(Set.of(2), resolved.pages());
        assertEquals(1, resolved.trimmedPages());
        assertEquals(1, result.counts().trimmedPages());
        assertEquals(0, result.counts().invalidEntries());
    }

    @Test
    void unretrievedAndNonNumericPages_areTrimmed() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"2, 7, iv\""));

        assertEquals(Set.of(2), result.resolvedById().get("1").pages());
        assertEquals(2, result.counts().trimmedPages());
        assertEquals(0, result.counts().invalidEntries());
    }

    @Test
    void pagesAsJsonArrayOrCommaString_resolveIdentically() {
        final ValidationResult fromArray = validate(entry(1, DOC_A_ID, DOC_A_FILE, "[2,3]"));
        final ValidationResult fromString = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"2,3\""));
        final ValidationResult fromStringArray = validate(entry(1, DOC_A_ID, DOC_A_FILE, "[\"2\",\" 3 \"]"));

        assertEquals(Set.of(2, 3), fromArray.resolvedById().get("1").pages());
        assertEquals(fromArray.resolvedById().get("1").pages(), fromString.resolvedById().get("1").pages());
        assertEquals(fromArray.resolvedById().get("1").pages(), fromStringArray.resolvedById().get("1").pages());
    }

    @Test
    void noSurvivingPage_isDroppedWithCausePages() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID, DOC_A_FILE, "\"7\""),
                entry(2, DOC_A_ID, DOC_A_FILE, "\"N/A\""),
                entry(3, DOC_A_ID, DOC_A_FILE, "\"\""),
                entry(4, DOC_A_ID, DOC_A_FILE, null),
                entry(5, DOC_A_ID, DOC_A_FILE, "[]"),
                entry(6, DOC_A_ID, DOC_A_FILE, "null"));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(6, result.counts().noValidPage());
        assertEquals(6, result.counts().invalidEntries());
        assertEquals(0, result.counts().trimmedPages(), "pages of dropped entries are not counted as trimmed");
    }

    @Test
    void rangeToken_isNotExpanded() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"2-3\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(1, result.counts().noValidPage());
    }

    @Test
    void romanAndDecimalPageTokens_areTrimmedWithoutException() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID, DOC_A_FILE, "\"iv,2\""),
                entry(2, DOC_A_ID, DOC_A_FILE, "[7.0, 3]"),
                entry(3, DOC_A_ID, DOC_A_FILE, "\"99999999999,3\""));

        assertEquals(Set.of(2), result.resolvedById().get("1").pages());
        assertEquals(Set.of(3), result.resolvedById().get("2").pages());
        assertEquals(Set.of(3), result.resolvedById().get("3").pages());
        assertEquals(3, result.counts().trimmedPages());
    }

    @Test
    void duplicateSurvivingPageTokens_areDeDuplicatedAndNotCountedAsTrimmed() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"3,2,3, ,\""));

        assertEquals(List.of(2, 3), List.copyOf(result.resolvedById().get("1").pages()));
        assertEquals(0, result.counts().trimmedPages());
    }

    @Test
    void numericAndStringCitationIds_shareAKey_firstValidEntryWins() {
        final ValidationResult result = validate(
                entry(1, GARBLED_A_ID, DOC_A_FILE, "\"2\""),
                entry("1", DOC_A_ID, DOC_A_FILE, "\"2\""));

        assertEquals(DOC_A_ID, result.resolvedById().get("1").document().documentId());
        assertEquals(1, result.counts().invalidEntries());
        assertTrue(result.droppedIds().isEmpty(), "id 1 still has a valid entry");
    }

    @Test
    void validDuplicateAfterTheFirst_isShadowedAndNotCounted() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry(1, DOC_B_ID, DOC_B_FILE, "\"5,9\""));

        assertEquals(DOC_A_ID, result.resolvedById().get("1").document().documentId());
        assertEquals(0, result.counts().invalidEntries());
        assertEquals(0, result.counts().trimmedPages(), "a shadowed entry's trims are not counted");
        assertEquals(2, result.counts().factMapEntries());
    }

    @Test
    void invalidDuplicateAfterTheFirst_isCounted() {
        final ValidationResult result = validate(
                entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry(1, DOC_A_ID, DOC_A_FILE, "\"7\""));

        assertEquals(Set.of(2), result.resolvedById().get("1").pages());
        assertEquals(1, result.counts().noValidPage());
    }

    @Test
    void citationIdIsTrimmed() {
        final ValidationResult result = validate(entry(" 4 ", DOC_A_ID, DOC_A_FILE, "\"2\""));

        assertEquals(Set.of("4"), result.resolvedById().keySet());
    }

    @Test
    void documentWhoseOnlyChunkHasNoPage_isNotCitable() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_B_ID, DOC_B_FILE, null, null)));

        final ValidationResult result = validate(scope,
                entry(1, DOC_B_ID, DOC_B_FILE, "\"5\""),
                entry(2, DOC_B_ID, DOC_B_FILE, "\"N/A\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(2, result.counts().invalidDocumentId());
    }

    @Test
    void documentWithSomeNullPages_staysCitableOnItsRetrievedPages() {
        final CitationScope scope = CitationScope.from(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_A_ID, DOC_A_FILE, null, null)));

        final ValidationResult result = validate(scope, entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""));

        assertEquals(Set.of(2), result.resolvedById().get("1").pages());
    }

    @Test
    void entryWithoutCitationId_isDroppedAndCounted() {
        final ValidationResult result = validate(
                entry(null, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry("  ", DOC_A_ID, DOC_A_FILE, "\"2\""),
                "{\"citationId\":null,\"documentId\":\"" + DOC_A_ID + "\"}");

        assertTrue(result.resolvedById().isEmpty());
        assertTrue(result.droppedIds().isEmpty(), "an entry with no id cannot leave a marker behind");
        assertEquals(3, result.counts().noCitationId());
        assertEquals(3, result.counts().invalidEntries());
    }

    @Test
    void nullEntryInTheArray_isCountedAsNoCitationId() {
        final ValidationResult result = CitationEntryValidator.validate(
                Arrays.asList(null, entries(array(entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""))).get(0)), SCOPE);

        assertEquals(1, result.counts().noCitationId());
        assertEquals(Set.of("1"), result.resolvedById().keySet());
    }

    @Test
    void eachEntryHasExactlyOneCause_theFirstFailingCheck() {
        final ValidationResult result = validate(
                entry(null, GARBLED_A_ID, DOC_B_FILE, "\"7\""),   // no id (and everything else wrong)
                entry(2, GARBLED_A_ID, DOC_B_FILE, "\"7\""),      // documentId (filename/pages also wrong)
                entry(3, DOC_A_ID, DOC_B_FILE, "\"7\""),          // filename (pages also wrong)
                entry(4, DOC_A_ID, DOC_A_FILE, "\"7\""),          // pages
                entry(5, DOC_B_ID, DOC_B_FILE, "\"5\""));         // valid

        final ValidationCounts counts = result.counts();
        assertEquals(new ValidationCounts(5, 1, 1, 1, 1, 0), counts);
        assertEquals(counts.noCitationId() + counts.invalidDocumentId() + counts.invalidFileName()
                + counts.noValidPage(), counts.invalidEntries());
        assertEquals(4, counts.invalidEntries());
        assertEquals(Set.of("2", "3", "4"), result.droppedIds());
    }

    @Test
    void emptyScope_dropsEveryEntry() {
        final ValidationResult result = validate(CitationScope.empty(),
                entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry(2, DOC_B_ID, DOC_B_FILE, "\"5\""));

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(2, result.counts().invalidEntries());
    }

    @Test
    void nullScope_isTreatedAsEmpty() {
        final ValidationResult result = CitationEntryValidator.validate(
                entries(array(entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""))), null);

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(1, result.counts().invalidDocumentId());
    }

    @Test
    void emptyEntryList_hasNoCounts() {
        final ValidationResult result = CitationEntryValidator.validate(List.of(), SCOPE);

        assertTrue(result.resolvedById().isEmpty());
        assertEquals(ValidationCounts.NONE, result.counts());
    }

    @Test
    void resolvedDocumentIsTheScopesDocument_notTheModelsCopy() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, "Report-A.PDF", "\"3,2\""));

        assertSame(SCOPE.document(DOC_A_ID).orElseThrow(), result.resolvedById().get("1").document());
    }

    @Test
    void resultMapsAreUnmodifiable() {
        final ValidationResult result = validate(entry(1, DOC_A_ID, DOC_A_FILE, "\"2\""),
                entry(2, GARBLED_A_ID, DOC_A_FILE, "\"2\""));

        assertThrows(UnsupportedOperationException.class, () -> result.resolvedById().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.droppedIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.resolvedById().get("1").pages().clear());
    }
}
