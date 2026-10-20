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
 * Validates the model's {@code <FACT_MAP_JSON>} entries against the {@link CitationScope} of the
 * chunks it was actually shown. Stateless; kept apart from {@link CitationProcessor} so every
 * rule is unit-testable on its own and the processor stays a readable pipeline.
 *
 * <p>Each entry is checked in this order and fails on the <strong>first</strong> broken rule, so
 * it carries exactly one cause:
 * <ol>
 *   <li>{@code citationId} is numeric ({@value #NUMERIC_ID}) once trimmed, so {@code 1} and
 *       {@code "1"} are the same key;</li>
 *   <li>{@code documentId} is a JSON string that <em>exactly</em> names a citable document in
 *       the scope (case-sensitive, no trimming, no repair);</li>
 *   <li>{@code documentFilename} matches that document's displayed filename (trimmed,
 *       case-insensitive);</li>
 *   <li>{@code individualPageNumbers} (JSON array or comma string) keeps only the integer tokens
 *       that are retrieved pages of that document; every other token is trimmed, and an entry
 *       with no surviving page is invalid.</li>
 * </ol>
 *
 * <p>The first <em>valid</em> entry per citationId wins. Invalid entries are always counted; a
 * valid entry shadowed by an earlier valid one is neither counted nor has its trims counted. The
 * model's {@code documentId}, {@code documentFilename} and {@code pageNumbers} values are never
 * carried forward — a resolved citation holds the scope's document and the surviving pages only.
 */
final class CitationEntryValidator {

    /** A citation id or page number: the only form a marker or a page can take. */
    static final String NUMERIC_ID = "\\d{1,9}";

    private static final Pattern NUMERIC = Pattern.compile(NUMERIC_ID);

    private CitationEntryValidator() {
    }

    /**
     * One citationId that survived validation (the id is its key in {@link ValidationResult}).
     *
     * @param document     the retrieved document it resolves to (never the model's copy)
     * @param pages        the surviving retrieved pages, ascending and de-duplicated
     * @param trimmedPages page tokens removed from this entry
     */
    record ResolvedCitation(CitableDocument document, SortedSet<Integer> pages, int trimmedPages) {
    }

    /**
     * @param resolvedById citationId → its first valid entry, in entry order
     * @param droppedIds   citationIds that appear on at least one entry but have no valid entry
     * @param counts       entry-level validation counts
     */
    record ValidationResult(Map<String, ResolvedCitation> resolvedById, Set<String> droppedIds,
                            ValidationCounts counts) {
    }

    /** Per-cause counters for one {@link #validate} call. */
    private static final class Tally {
        private int invalidCitationId;
        private int invalidDocumentId;
        private int invalidFileName;
        private int noValidPage;
        private int trimmedPages;
    }

    /**
     * Validates every entry, in order, against {@code scope}. A {@code null} scope is treated as
     * {@link CitationScope#empty()} — every entry is invalid, validation is never skipped.
     */
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

    /** The trimmed, numeric citationId of an entry, or {@code null} when missing or not numeric. */
    private static String citationIdOf(final Map<String, Object> entry) {
        final Object value = entry == null ? null : entry.get(CITATION_ID);
        final String citationId = value == null ? null : String.valueOf(value).trim();
        return citationId != null && NUMERIC.matcher(citationId).matches() ? citationId : null;
    }

    /** The resolved citation for a valid entry, or {@code null} after counting its cause in {@code tally}. */
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

    /** Page tokens of a JSON array or a comma string; every value, array elements included, is
     *  split on ',' — so {@code ["2,3"]} reads as pages 2 and 3 — then trimmed, empties ignored. */
    private static List<String> pageTokens(final Object pages) {
        final Stream<?> values = pages instanceof final List<?> list ? list.stream() : Stream.ofNullable(pages);
        return values.flatMap(value -> Arrays.stream(String.valueOf(value).split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
