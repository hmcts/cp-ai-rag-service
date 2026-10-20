package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.retrieval.model.CitationKeys.CITATION_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.INDIVIDUAL_PAGE_NUMBERS;

import uk.gov.moj.cp.ai.util.StringUtil;
import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.model.CitationScope.CitableDocument;
import uk.gov.moj.cp.retrieval.service.CitationProcessor.ValidationCounts;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Validates the model's {@code <FACT_MAP_JSON>} entries against the {@link CitationScope} of the
 * chunks it was actually shown. Stateless; kept apart from {@link CitationProcessor} so every
 * rule is unit-testable on its own and the processor stays a readable pipeline.
 *
 * <p>Each entry is checked in this order and fails on the <strong>first</strong> broken rule, so
 * it carries exactly one cause:
 * <ol>
 *   <li>{@code citationId} present and non-blank — stringified then trimmed, so {@code 1} and
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

    private CitationEntryValidator() {
    }

    /**
     * One citationId that survived validation.
     *
     * @param citationId   the trimmed citationId key
     * @param document     the retrieved document it resolves to (never the model's copy)
     * @param pages        the surviving retrieved pages, ascending and de-duplicated
     * @param trimmedPages page tokens removed from this entry
     */
    record ResolvedCitation(String citationId, CitableDocument document, SortedSet<Integer> pages,
                            int trimmedPages) {

        ResolvedCitation {
            pages = Collections.unmodifiableSortedSet(new TreeSet<>(pages));
        }
    }

    /**
     * @param resolvedById citationId → its first valid entry, in entry order
     * @param droppedIds   citationIds that appear on at least one entry but have no valid entry
     * @param counts       entry-level validation counts
     */
    record ValidationResult(Map<String, ResolvedCitation> resolvedById, Set<String> droppedIds,
                            ValidationCounts counts) {
    }

    /** Why an entry was dropped — exactly one per invalid entry. */
    private enum Cause { NO_CITATION_ID, DOCUMENT_ID, FILE_NAME, PAGES }

    /** The result of checking one entry: either a resolved citation or the cause it failed on. */
    private record Check(ResolvedCitation resolved, Cause cause) {

        static Check valid(final ResolvedCitation resolved) {
            return new Check(resolved, null);
        }

        static Check invalid(final Cause cause) {
            return new Check(null, cause);
        }
    }

    /**
     * Validates every entry, in order, against {@code scope}. A {@code null} scope is treated as
     * {@link CitationScope#empty()} — every entry is invalid, validation is never skipped.
     */
    static ValidationResult validate(final List<Map<String, Object>> entries, final CitationScope scope) {
        final CitationScope effectiveScope = scope == null ? CitationScope.empty() : scope;
        final List<Map<String, Object>> effectiveEntries = entries == null ? List.of() : entries;

        final Map<String, ResolvedCitation> resolvedById = new LinkedHashMap<>();
        final Set<String> seenIds = new LinkedHashSet<>();
        final Map<Cause, Integer> causes = new EnumMap<>(Cause.class);
        int trimmedPages = 0;

        for (final Map<String, Object> entry : effectiveEntries) {
            final String citationId = citationIdOf(entry);
            final Check check = citationId == null
                    ? Check.invalid(Cause.NO_CITATION_ID)
                    : check(entry, citationId, effectiveScope);
            if (citationId != null) {
                seenIds.add(citationId);
            }
            if (check.cause() != null) {
                causes.merge(check.cause(), 1, Integer::sum);
            } else if (resolvedById.putIfAbsent(citationId, check.resolved()) == null) {
                trimmedPages += check.resolved().trimmedPages();
            }
        }

        final Set<String> droppedIds = new LinkedHashSet<>(seenIds);
        droppedIds.removeAll(resolvedById.keySet());

        final ValidationCounts counts = new ValidationCounts(effectiveEntries.size(),
                causes.getOrDefault(Cause.NO_CITATION_ID, 0), causes.getOrDefault(Cause.DOCUMENT_ID, 0),
                causes.getOrDefault(Cause.FILE_NAME, 0), causes.getOrDefault(Cause.PAGES, 0), trimmedPages);
        return new ValidationResult(Collections.unmodifiableMap(resolvedById),
                Collections.unmodifiableSet(droppedIds), counts);
    }

    /** The trimmed citationId key of an entry, or {@code null} when missing or blank. */
    private static String citationIdOf(final Map<String, Object> entry) {
        if (entry == null) {
            return null;
        }
        final String citationId = stringifyJsonValue(entry.get(CITATION_ID));
        return StringUtil.isNullOrEmpty(citationId) ? null : citationId.trim();
    }

    private static Check check(final Map<String, Object> entry, final String citationId, final CitationScope scope) {
        final Optional<CitableDocument> document = entry.get(DOCUMENT_ID) instanceof final String documentId
                ? scope.document(documentId)
                : Optional.empty();
        if (document.isEmpty()) {
            return Check.invalid(Cause.DOCUMENT_ID);
        }
        final CitableDocument citable = document.get();
        if (!(entry.get(DOCUMENT_FILE_NAME) instanceof final String fileName) || !citable.matchesFileName(fileName)) {
            return Check.invalid(Cause.FILE_NAME);
        }

        final SortedSet<Integer> surviving = new TreeSet<>();
        int trimmed = 0;
        for (final String token : pageTokens(entry.get(INDIVIDUAL_PAGE_NUMBERS))) {
            final Integer page = parsePage(token);
            if (page != null && citable.pages().contains(page)) {
                surviving.add(page);
            } else {
                trimmed++;
            }
        }
        if (surviving.isEmpty()) {
            return Check.invalid(Cause.PAGES);
        }
        return Check.valid(new ResolvedCitation(citationId, citable, surviving, trimmed));
    }

    /** Page tokens of a JSON array or comma string: split on ',', trimmed, empties ignored. */
    private static List<String> pageTokens(final Object pages) {
        final String joined = stringifyJsonValue(pages);
        if (StringUtil.isNullOrEmpty(joined)) {
            return List.of();
        }
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /** The integer page a token names, or {@code null} for anything else ({@code iv}, {@code 2-3}, {@code 7.0}). */
    private static Integer parsePage(final String token) {
        try {
            return Integer.parseInt(token);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** A JSON value as text: a JSON array is joined with {@code ,}; {@code null} stays {@code null}. */
    private static String stringifyJsonValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof final List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }
}
