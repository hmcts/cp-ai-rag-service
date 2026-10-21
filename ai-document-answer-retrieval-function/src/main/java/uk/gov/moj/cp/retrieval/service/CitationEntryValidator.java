package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.retrieval.model.CitationKeys.CITATION_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.INDIVIDUAL_PAGE_NUMBERS;

import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.model.CitationScope.CitableDocument;
import uk.gov.moj.cp.retrieval.model.ValidationCounts;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates {@code <FACT_MAP_JSON>} entries against the {@link CitationScope} the model was shown.
 * An entry fails on the first broken rule, so it has exactly one cause:
 * <ol>
 *   <li>{@code citationId}, trimmed, is numeric ({@code 1} and {@code "1"} are the same key);</li>
 *   <li>{@code documentId} exactly names a citable document (no trimming, case folding or repair);</li>
 *   <li>{@code documentFilename} matches that document's displayed filename;</li>
 *   <li>at least one {@code individualPageNumbers} token is a retrieved page of that document;
 *       other tokens are trimmed.</li>
 * </ol>
 * The first valid entry per id wins; later valid duplicates are neither counted nor trimmed.
 * Nothing the model wrote is carried forward except the surviving pages.
 */
final class CitationEntryValidator {

    /** Citation id and page number form, shared by every marker pattern; bounded so no pattern
     *  built on it can backtrack super-linearly (S5852), and 9 digits always fit an int. */
    static final String NUMERIC_ID = "\\d{1,9}";

    private static final Pattern NUMERIC = Pattern.compile(NUMERIC_ID);

    private CitationEntryValidator() {
    }

    /** A valid entry, keyed by its id in {@link ValidationResult#resolvedById()}. */
    record ResolvedCitation(CitableDocument document, SortedSet<Integer> pages, int trimmedPages) {
    }

    /** @param droppedIds ids that appear on some entry but have no valid one */
    record ValidationResult(Map<String, ResolvedCitation> resolvedById, Set<String> droppedIds,
                            ValidationCounts counts) {
    }

    private static final class Tally {
        private int invalidCitationId;
        private int invalidDocumentId;
        private int invalidFileName;
        private int noValidPage;
        private int trimmedPages;
    }

    /** @param scope {@code null} means no entry can be valid */
    static ValidationResult validate(final List<Map<String, Object>> entries, final CitationScope scope) {
        final CitationScope effectiveScope = scope == null ? CitationScope.empty() : scope;
        final List<Map<String, Object>> effectiveEntries = entries == null ? List.of() : entries;

        final Map<String, ResolvedCitation> resolvedById = new LinkedHashMap<>();
        final Set<String> droppedIds = new LinkedHashSet<>();
        final Tally tally = new Tally();
        for (final Map<String, Object> entry : effectiveEntries) {
            final String citationId = citationIdOf(entry);
            if (citationId == null) {
                tally.invalidCitationId++;
                continue;
            }
            droppedIds.add(citationId);
            final ResolvedCitation resolved = check(entry, effectiveScope, tally);
            if (resolved != null && resolvedById.putIfAbsent(citationId, resolved) == null) {
                tally.trimmedPages += resolved.trimmedPages();
            }
        }
        droppedIds.removeAll(resolvedById.keySet());

        final ValidationCounts counts = new ValidationCounts(effectiveEntries.size(), tally.invalidCitationId,
                tally.invalidDocumentId, tally.invalidFileName, tally.noValidPage, tally.trimmedPages);
        return new ValidationResult(Collections.unmodifiableMap(resolvedById),
                Collections.unmodifiableSet(droppedIds), counts);
    }

    private static String citationIdOf(final Map<String, Object> entry) {
        final Object value = entry == null ? null : entry.get(CITATION_ID);
        final String citationId = value == null ? null : String.valueOf(value).trim();
        return citationId != null && NUMERIC.matcher(citationId).matches() ? citationId : null;
    }

    /** {@code null} after counting the entry's cause in {@code tally}. */
    private static ResolvedCitation check(final Map<String, Object> entry, final CitationScope scope,
                                          final Tally tally) {
        final CitableDocument document = entry.get(DOCUMENT_ID) instanceof final String documentId
                ? scope.document(documentId).orElse(null)
                : null;
        if (document == null) {
            tally.invalidDocumentId++;
            return null;
        }
        if (!(entry.get(DOCUMENT_FILE_NAME) instanceof final String fileName) || !document.matchesFileName(fileName)) {
            tally.invalidFileName++;
            return null;
        }

        final SortedSet<Integer> surviving = new TreeSet<>();
        int trimmed = 0;
        for (final String token : pageTokens(entry.get(INDIVIDUAL_PAGE_NUMBERS))) {
            if (NUMERIC.matcher(token).matches() && document.pages().contains(Integer.parseInt(token))) {
                surviving.add(Integer.parseInt(token));
            } else {
                trimmed++;
            }
        }
        if (surviving.isEmpty()) {
            tally.noValidPage++;
            return null;
        }
        return new ResolvedCitation(document, Collections.unmodifiableSortedSet(surviving), trimmed);
    }

    /** Array elements are split on ',' too, so {@code ["2,3"]} reads as pages 2 and 3. */
    private static List<String> pageTokens(final Object pages) {
        final Stream<?> values = pages instanceof final List<?> list ? list.stream() : Stream.ofNullable(pages);
        return values.flatMap(value -> Arrays.stream(String.valueOf(value).split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
