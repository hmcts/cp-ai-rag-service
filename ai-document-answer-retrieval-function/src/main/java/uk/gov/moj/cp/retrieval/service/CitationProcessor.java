package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.FACT_MAP_ATTRIBUTE_KEY;

import uk.gov.moj.cp.ai.util.StringUtil;
import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.model.CitationScope.CitableDocument;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ResolvedCitation;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ValidationResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-processes raw LLM output into the user-visible citation format.
 *
 * <p>Extracts the {@code <FACT_MAP_JSON>} payload, validates every entry against the chunks the
 * model was actually shown, substitutes the inline {@code [N]} placeholders of the valid entries
 * with the human-readable source string, and applies a series of tolerance rules covering the
 * LLM output drift modes observed in production with GPT-4o and GPT-4.1.
 *
 * <p>Validation (against the per-answer {@link CitationScope}; see {@link CitationEntryValidator}):
 * <ul>
 *   <li>An entry is kept only when its {@code documentId} exactly names a retrieved document, its
 *       {@code documentFilename} matches the filename that document was shown with (trimmed,
 *       case-insensitive), and at least one of its {@code individualPageNumbers} is a retrieved
 *       page of that document. Unretrieved or non-numeric pages are trimmed from a kept entry.</li>
 *   <li>Invalid entries are dropped, never repaired. Markers of ids with no valid entry — whether
 *       every entry for the id was invalid or the id had no entry at all — are removed
 *       <strong>before</strong> run detection, so the surrounding same-document markers still
 *       merge.</li>
 *   <li>A kept citation renders the documentId, filename and pages from retrieval, never the
 *       model's copies; the compressed page form is always recomputed from the surviving pages.
 *       A {@code null} or empty scope makes every entry invalid — validation is never skipped.</li>
 *   <li>Rendered citations stay behind opaque sentinels ({@link RenderedCitations}) until the
 *       final step, so a filename that looks like a marker or a citation is never substituted,
 *       stripped or counted by a later pass.</li>
 * </ul>
 *
 * <p>Tolerance rules implemented:
 * <ul>
 *   <li>Uses the <strong>last</strong> {@code <FACT_MAP_JSON>} block rather
 *       than the first, so an echoed example tag at the start of the response
 *       does not cause the parser to discard the real answer body.</li>
 *   <li>Preserves prose on both sides of the citation tag, so responses where
 *       the model emits the JSON first or in the middle still render.</li>
 *   <li>Matches the placeholder against a tolerant regex covering common LLM
 *       drift forms: {@code [N]}, {@code [N p.X]}, {@code [N, p.X]},
 *       {@code [N:X]}, {@code [^N]}, {@code [Source N]}, {@code [doc N]},
 *       {@code [Citation N]}, {@code [Ref N]} (label case-insensitive).</li>
 *   <li>Expands comma- or semicolon-joined IDs such as {@code [1, 2]} into the
 *       canonical {@code [1][2]} form before substitution.</li>
 *   <li>Strips markdown {@code ```json} code fences a model may wrap around
 *       the JSON payload.</li>
 *   <li>Accepts opening/closing tags emitted in any letter case and with
 *       whitespace inside the angle brackets.</li>
 *   <li>Merges <em>same-document stacked runs</em>: a run of adjacent bare
 *       placeholders (e.g. {@code [2][3]}) whose citationIds resolve to the
 *       <strong>same</strong> validated retrieval documentId is collapsed into a
 *       single formatted citation carrying the sorted, de-duplicated union of the
 *       entries' surviving pages. Adjacent placeholders citing <em>different</em>
 *       documents are legitimate multi-document support and are left separate. The
 *       merge is positional, so the same id appearing non-adjacently elsewhere still
 *       substitutes normally.</li>
 *   <li>Strips <em>unresolved</em> bare {@code [N]} markers — any inline
 *       placeholder with no valid JSON entry, or left over because the
 *       {@code <FACT_MAP_JSON>} block was absent or the JSON failed to parse. This
 *       covers two failure modes: the GPT-4o catastrophic counter-loop (output
 *       flooded with {@code [1][2]...[N]}) and the GPT-5.1 reasoning-token
 *       truncation, where the response is cut off before the closing tag so the
 *       citation block is lost entirely. In both cases the user would otherwise see
 *       naked, meaningless brackets.</li>
 * </ul>
 */
public class CitationProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CitationProcessor.class);

    /** Matches the LLM citation block, case-insensitive and whitespace-tolerant in the tag. */
    private static final Pattern JSON_TAG_PATTERN = Pattern.compile(
            "<\\s*" + FACT_MAP_ATTRIBUTE_KEY + "\\s*>(.*?)<\\s*/\\s*" + FACT_MAP_ATTRIBUTE_KEY + "\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Markdown code fences a model may wrap around the JSON payload. Stripped before parsing. */
    private static final Pattern CODE_FENCE_OPEN =
            Pattern.compile("\\A\\s*```(?:json|JSON)?\\s*", Pattern.DOTALL);
    private static final Pattern CODE_FENCE_CLOSE =
            Pattern.compile("\\s*```\\s*\\z", Pattern.DOTALL);

    /** Matches comma- or semicolon-joined citation IDs in a single bracket, e.g. {@code [1, 2]}. */
    private static final Pattern JOINED_IDS_PATTERN =
            Pattern.compile("\\[(\\d+(?:\\s*[,;]\\s*\\d+)+)\\]");

    /** Splitter for the joined-id pattern groups; matches "comma or semicolon optionally surrounded by whitespace". */
    private static final Pattern JOINED_IDS_SEPARATOR = Pattern.compile("\\s*[,;]\\s*");

    /** Matches any bare {@code [N]} integer-in-brackets. Used to detect counter-loop pathology. */
    private static final Pattern BARE_BRACKET_INT = Pattern.compile("\\[\\d+\\]");

    /** A maximal run of two or more adjacent bare {@code [N]} markers, separated by at most a few
     *  horizontal whitespace characters (a newline between markers is NOT a run — citations of
     *  separate list items must not merge). Every quantifier is bounded and each repetition is
     *  anchored on a literal '[', so the pattern cannot backtrack super-linearly (S5852). */
    private static final Pattern MARKER_RUN_PATTERN =
            Pattern.compile("\\[\\d{1,4}\\](?:[ \\t]{0,3}\\[\\d{1,4}\\]){1,50}");

    /** A single bare {@code [N]} marker within a matched run; group 1 is the citation id. */
    private static final Pattern SINGLE_MARKER_PATTERN = Pattern.compile("\\[(\\d{1,4})\\]");

    /** A bare {@code [N]} marker with the (at most run-gap sized) horizontal whitespace before it;
     *  group 1 is that whitespace, group 2 the citation id. Bounded quantifiers only (S5852). */
    private static final Pattern MARKER_WITH_GAP_PATTERN = Pattern.compile("([ \\t]{0,3})\\[(\\d{1,9})\\]");

    /** Above this many surviving {@code [N]} markers, the output is treated as catastrophic. */
    private static final int CATASTROPHIC_BRACKET_THRESHOLD = 100;

    /** Placeholder regex template; the {@code %s} is substituted with the citation id. */
    private static final String PLACEHOLDER_REGEX_TEMPLATE =
            "(?i)\\[\\^?(?:(?:Source|doc|Citation|Ref)[\\s:]+)?\\s*%s(?:[\\s,;:][^\\[\\]]*)?\\]";

    /** The user-visible citation format. */
    private static final String CITATION_FORMAT = "::(Source: [%s], Pages %s|%s|documentId=%s)";

    private static final String NOT_AVAILABLE = "N/A";

    private final ObjectMapper objectMapper = getObjectMapper();

    /**
     * The outcome of citation post-processing: the formatted text plus the degradation signal
     * that was previously log-only. Consumed by the citation guard in
     * {@code ResponseGenerationService} to decide whether an answer may progress.
     *
     * @param formattedText      the user-visible answer after validation/substitution/merge/stripping
     * @param jsonBlockPresent   a complete {@code <FACT_MAP_JSON>} block was found
     * @param emptyFactMap       the block parsed to an empty array (deliberate no-evidence refusal);
     *                           computed on the original list, so "every entry invalid" is never a refusal
     * @param inlineMarkers      bare {@code [N]} markers the model wrote (after joined-id normalisation)
     * @param renderedCitations  citations actually rendered into the formatted text
     * @param strippedMarkers    markers removed without being rendered: those of ids with no valid
     *                           entry (including drift forms of dropped ids) and any leftovers
     * @param validation         entry-level validation counts; {@link ValidationCounts#NONE} when
     *                           there was no parseable block
     */
    public record CitationOutcome(String formattedText, boolean jsonBlockPresent,
                                  boolean emptyFactMap, int inlineMarkers,
                                  int renderedCitations, int strippedMarkers,
                                  ValidationCounts validation) {

        /** {@code FACT_MAP_JSON} entries dropped by validation. */
        public int invalidEntries() {
            return validation.invalidEntries();
        }

        /** Page tokens trimmed from the citations that were kept. */
        public int trimmedPages() {
            return validation.trimmedPages();
        }
    }

    /**
     * Entry-level counts from validating the {@code <FACT_MAP_JSON>} entries against the
     * retrieved chunks. Each invalid entry carries exactly one cause (the first failing check),
     * so {@link #invalidEntries()} is the sum of the four cause counts.
     *
     * @param factMapEntries    entries in the parsed list (0 when there was no parseable block)
     * @param noCitationId      entries dropped for a missing or blank {@code citationId}
     * @param invalidDocumentId entries dropped because the documentId named no retrieved document
     * @param invalidFileName   entries dropped because the filename did not match that document
     * @param noValidPage       entries dropped because none of their pages was retrieved
     * @param trimmedPages      page tokens removed from the entries that were kept
     */
    public record ValidationCounts(int factMapEntries, int noCitationId, int invalidDocumentId,
                                   int invalidFileName, int noValidPage, int trimmedPages) {

        /** No entries were validated: no block, unparseable JSON, or empty input. */
        public static final ValidationCounts NONE = new ValidationCounts(0, 0, 0, 0, 0, 0);

        /** Entries (not markers, not ids) that failed validation. */
        public int invalidEntries() {
            return noCitationId + invalidDocumentId + invalidFileName + noValidPage;
        }
    }

    /**
     * Executes the post-processing pipeline: extracts structured citations, validates them
     * against {@code scope} and substitutes the inline numerical placeholders of the valid ones
     * with the fully formatted citation string.
     *
     * @param rawLlmOutput the raw text output containing the narrative and the embedded JSON
     * @param scope        what the model was shown; {@code null} is treated as {@link CitationScope#empty()}
     * @return the clean, fully cited response text, or an empty string if the input is empty
     */
    public String processAndFormatCitations(final String rawLlmOutput, final CitationScope scope) {
        return processCitations(rawLlmOutput, scope).formattedText();
    }

    /**
     * Same pipeline as {@link #processAndFormatCitations}, returning the full
     * {@link CitationOutcome} so callers can act on citation degradation instead of it
     * being visible only in the logs.
     *
     * @param rawLlmOutput the raw text output containing the narrative and the embedded JSON
     * @param scope        what the model was shown; {@code null} is treated as {@link CitationScope#empty()}
     *                     (every entry invalid — validation is never skipped)
     */
    public CitationOutcome processCitations(final String rawLlmOutput, final CitationScope scope) {
        if (StringUtil.isNullOrEmpty(rawLlmOutput)) {
            return new CitationOutcome("", false, false, 0, 0, 0, ValidationCounts.NONE);
        }
        return process(RenderedCitations.withoutSentinelDelimiter(rawLlmOutput), scope);
    }

    private CitationOutcome process(final String rawLlmOutput, final CitationScope scope) {

        final TagLocation lastTag = findLastJsonTag(rawLlmOutput);
        if (lastTag == null) {
            LOGGER.warn("Raw output missing required <{}> tags; cannot format citations.", FACT_MAP_ATTRIBUTE_KEY);
            final String normalised = normaliseJoinedIds(rawLlmOutput);
            final int markers = countBareMarkers(normalised);
            return new CitationOutcome(stripUnresolvedCitationMarkers(normalised, true).trim(),
                    false, false, markers, 0, markers, ValidationCounts.NONE);
        }

        final String answerBeforeTag = rawLlmOutput.substring(0, lastTag.start);
        final String answerAfterTag = rawLlmOutput.substring(lastTag.end);
        final String answerText = normaliseJoinedIds((answerBeforeTag + " " + answerAfterTag).trim());
        final int inlineMarkers = countBareMarkers(answerText);

        final String jsonPayload = stripCodeFences(lastTag.payload).trim();

        try {
            final List<Map<String, Object>> citations =
                    objectMapper.readValue(jsonPayload, new TypeReference<>() { });
            return applyCitations(answerText, citations, inlineMarkers,
                    scope == null ? CitationScope.empty() : scope);
        } catch (final Exception e) {
            LOGGER.warn("Failed to parse citation JSON or substitute placeholders; returning narrative without citations.", e);
            return new CitationOutcome(stripUnresolvedCitationMarkers(answerText, true).trim(),
                    true, false, inlineMarkers, 0, inlineMarkers, ValidationCounts.NONE);
        }
    }

    /**
     * Steps after a successful parse: validate, remove the markers of ids with no valid entry,
     * merge same-document runs, substitute from the scope, strip any leftover marker, and only
     * then expand the rendered citations — so no marker pass ever sees a rendered filename.
     */
    private static CitationOutcome applyCitations(final String answerText, final List<Map<String, Object>> citations,
                                                  final int inlineMarkers, final CitationScope scope) {
        // Refusal detection uses the ORIGINAL list: "every entry invalid" is never a refusal.
        final boolean emptyFactMap = citations.isEmpty();
        final ValidationResult validation = CitationEntryValidator.validate(citations, scope);

        final MarkerRemoval removal = removeUnresolvableMarkers(answerText, validation);
        final RenderedCitations rendered = new RenderedCitations();
        String text = mergeSameDocumentRuns(removal.text(), validation.resolvedById(), rendered);
        for (final ResolvedCitation resolved : validation.resolvedById().values()) {
            text = substituteCitation(text, resolved, rendered);
        }
        final int leftover = countBareMarkers(text);
        final String withSentinels = stripUnresolvedCitationMarkers(text, false).trim();
        return new CitationOutcome(rendered.expand(withSentinels), true, emptyFactMap, inlineMarkers,
                rendered.count(withSentinels), removal.removed() + leftover, validation.counts());
    }

    private static int countBareMarkers(final String text) {
        return (int) BARE_BRACKET_INT.matcher(text).results().count();
    }

    private static TagLocation findLastJsonTag(final String rawLlmOutput) {
        final Matcher matcher = JSON_TAG_PATTERN.matcher(rawLlmOutput);
        TagLocation last = null;
        while (matcher.find()) {
            last = new TagLocation(matcher.start(), matcher.end(), matcher.group(1));
        }
        return last;
    }

    private static String normaliseJoinedIds(final String text) {
        return JOINED_IDS_PATTERN.matcher(text).replaceAll(match -> {
            final String[] ids = JOINED_IDS_SEPARATOR.split(match.group(1));
            final StringBuilder out = new StringBuilder(ids.length * 4);
            for (final String id : ids) {
                out.append('[').append(id).append(']');
            }
            return Matcher.quoteReplacement(out.toString());
        });
    }

    private static String stripCodeFences(final String payload) {
        final String afterOpen = CODE_FENCE_OPEN.matcher(payload).replaceFirst("");
        return CODE_FENCE_CLOSE.matcher(afterOpen).replaceFirst("");
    }

    /**
     * Removes, before run detection, every inline marker whose id has no valid entry: bare
     * {@code [N]} markers of dropped ids and of ids with no entry at all, then the tolerant drift
     * forms ({@code [Source N]}, {@code [N p.7]} …) of dropped ids, which would otherwise be left
     * as literal brackets.
     */
    private static MarkerRemoval removeUnresolvableMarkers(final String text, final ValidationResult validation) {
        final MarkerRemoval bare = removeUnresolvableBareMarkers(text, validation);
        final MarkerRemoval drift = removeDriftForms(bare.text(), validation.droppedIds());
        return new MarkerRemoval(drift.text(), bare.removed() + drift.removed());
    }

    /**
     * Removes each bare {@code [N]} marker whose id is not resolved. A marker that sits inside a
     * run (directly after a {@code ]}) takes its separating whitespace with it, so its neighbours
     * still form a run and merge; elsewhere only the marker itself is removed.
     */
    private static MarkerRemoval removeUnresolvableBareMarkers(final String text, final ValidationResult validation) {
        final Matcher marker = MARKER_WITH_GAP_PATTERN.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        int dropped = 0;
        int orphans = 0;
        while (marker.find()) {
            final String id = marker.group(2);
            if (validation.resolvedById().containsKey(id)) {
                continue;
            }
            if (validation.droppedIds().contains(id)) {
                dropped++;
            } else {
                orphans++;
            }
            final boolean insideRun = marker.start() > 0 && text.charAt(marker.start() - 1) == ']';
            out.append(text, last, insideRun ? marker.start() : marker.end(1));
            last = marker.end();
        }
        logRemovedMarkers(dropped, orphans);
        return new MarkerRemoval(out.append(text, last, text.length()).toString(), dropped + orphans);
    }

    /** Removes every tolerant-form placeholder ({@code [Source N]}, {@code [N p.7]} …) of the dropped ids. */
    private static MarkerRemoval removeDriftForms(final String text, final Collection<String> droppedIds) {
        String remaining = text;
        int removed = 0;
        for (final String droppedId : droppedIds) {
            final Matcher drift = Pattern.compile(String.format(PLACEHOLDER_REGEX_TEMPLATE, Pattern.quote(droppedId)))
                    .matcher(remaining);
            final long found = drift.results().count();
            if (found > 0) {
                removed += (int) found;
                remaining = drift.replaceAll("");
            }
        }
        return new MarkerRemoval(remaining, removed);
    }

    /**
     * Markers of entries that failed validation are expected and counted by the caller, so they
     * are DEBUG only; the existing WARNs for orphan markers and the counter-loop are kept.
     */
    private static void logRemovedMarkers(final int dropped, final int orphans) {
        if (dropped + orphans > CATASTROPHIC_BRACKET_THRESHOLD) {
            LOGGER.warn("Catastrophic citation pathology: {} unresolved [N] markers; stripping all.", dropped + orphans);
        } else if (orphans > 0) {
            LOGGER.warn("{} inline [N] marker(s) had no matching JSON citation entry; stripping unresolved markers.",
                    orphans);
        }
        if (dropped > 0) {
            LOGGER.debug("Removed {} inline [N] marker(s) of citation entries that failed validation", dropped);
        }
    }

    private static String substituteCitation(final String answerText, final ResolvedCitation resolved,
                                             final RenderedCitations rendered) {
        final String sentinel = rendered.register(formatCitation(resolved.document(), resolved.pages()));
        final String placeholderRegex = String.format(PLACEHOLDER_REGEX_TEMPLATE, Pattern.quote(resolved.citationId()));
        return answerText.replaceAll(placeholderRegex, Matcher.quoteReplacement(sentinel));
    }

    /** Renders one citation entirely from retrieval: displayed filename, documentId and pages. */
    private static String formatCitation(final CitableDocument document, final Collection<Integer> pages) {
        final String individual = pages.stream().map(String::valueOf).collect(Collectors.joining(","));
        return String.format(CITATION_FORMAT, document.displayedFileName(), compressPageRange(individual),
                individual, document.documentId());
    }

    /**
     * Collapses each maximal run of adjacent bare {@code [N]} markers whose citationIds resolve
     * to the same retrieval documentId into a single formatted citation carrying the union of
     * the entries' surviving pages. The rewrite is positional — only the matched run region
     * changes, so an id reused non-adjacently elsewhere still substitutes normally. Every marker
     * left at this point resolves (unresolvable ones were removed first); runs or portions of
     * runs citing different documents are re-emitted verbatim for the ordinary substitution.
     */
    private static String mergeSameDocumentRuns(final String answerText,
                                                final Map<String, ResolvedCitation> resolvedById,
                                                final RenderedCitations rendered) {
        if (resolvedById.isEmpty()) {
            return answerText;
        }

        final Matcher run = MARKER_RUN_PATTERN.matcher(answerText);
        final StringBuilder out = new StringBuilder(answerText.length());
        int last = 0;
        while (run.find()) {
            out.append(answerText, last, run.start());
            out.append(buildRunReplacement(run.group(), resolvedById, rendered));
            last = run.end();
        }
        return out.append(answerText, last, answerText.length()).toString();
    }

    /**
     * Rewrites one adjacent-marker run: maximal consecutive ids resolving to the same document
     * become one merged citation; every other id is re-emitted as its original {@code [N]}.
     */
    private static String buildRunReplacement(final String runText, final Map<String, ResolvedCitation> resolvedById,
                                              final RenderedCitations rendered) {
        final List<String> ids = new ArrayList<>();
        final Matcher marker = SINGLE_MARKER_PATTERN.matcher(runText);
        while (marker.find()) {
            ids.add(marker.group(1));
        }

        final StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < ids.size()) {
            final String documentId = documentIdOf(resolvedById.get(ids.get(i)));
            int j = i + 1;
            while (documentId != null && j < ids.size()
                    && documentId.equals(documentIdOf(resolvedById.get(ids.get(j))))) {
                j++;
            }
            appendGroup(out, ids.subList(i, j), resolvedById, rendered);
            i = j;
        }
        return out.toString();
    }

    private static void appendGroup(final StringBuilder out, final List<String> groupIds,
                                    final Map<String, ResolvedCitation> resolvedById,
                                    final RenderedCitations rendered) {
        if (groupIds.size() < 2) {
            out.append('[').append(groupIds.get(0)).append(']');
            return;
        }
        out.append(rendered.register(formatMergedCitation(groupIds, resolvedById)));
        LOGGER.info("Merged {} adjacent same-document citation markers {} into one citation",
                groupIds.size(), groupIds);
    }

    /** One citation for a same-document group: the union of the entries' surviving pages. */
    private static String formatMergedCitation(final List<String> groupIds,
                                               final Map<String, ResolvedCitation> resolvedById) {
        final SortedSet<Integer> union = new TreeSet<>();
        for (final String id : new LinkedHashSet<>(groupIds)) {
            union.addAll(resolvedById.get(id).pages());
        }
        return formatCitation(resolvedById.get(groupIds.get(0)).document(), union);
    }

    private static String documentIdOf(final ResolvedCitation resolved) {
        return resolved == null ? null : resolved.document().documentId();
    }

    static String stringifyJsonValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof final List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    /**
     * Removes inline {@code [N]} markers that could not be resolved to a citation.
     * A marker is unresolved when there was no {@code <FACT_MAP_JSON>} block, the
     * JSON failed to parse, or — as a final safety net after substitution — anything
     * the earlier removal of unresolvable markers did not catch. In all of these
     * cases the bracket is meaningless to the reader and is stripped.
     *
     * <p>Joined forms such as {@code [1, 2]} are normalised to {@code [1][2]} first
     * so they are caught too. The log level is the same warning in every case, but
     * the message distinguishes the catastrophic counter-loop, the missing-block
     * (truncation) case, and ordinary id/JSON mismatch for observability.
     *
     * @param text        the text to clean
     * @param noJsonBlock {@code true} when no parseable JSON block was available
     *                    (no-tag or parse-failure paths); {@code false} on the
     *                    post-substitution path where any leftover marker is a
     *                    plain inline⇄JSON id mismatch
     */
    private static String stripUnresolvedCitationMarkers(final String text, final boolean noJsonBlock) {
        final String normalised = normaliseJoinedIds(text);
        final long count = BARE_BRACKET_INT.matcher(normalised).results().count();
        if (count == 0) {
            return normalised;
        }
        if (count > CATASTROPHIC_BRACKET_THRESHOLD) {
            LOGGER.warn("Catastrophic citation pathology: {} unresolved [N] markers; stripping all.", count);
        } else if (noJsonBlock) {
            LOGGER.warn("Response carried {} inline [N] marker(s) but no usable <{}> block "
                            + "(likely reasoning-token truncation); stripping unresolved markers.",
                    count, FACT_MAP_ATTRIBUTE_KEY);
        } else {
            LOGGER.warn("{} inline [N] marker(s) had no matching JSON citation entry; stripping unresolved markers.",
                    count);
        }
        return BARE_BRACKET_INT.matcher(normalised).replaceAll("");
    }

    /**
     * Compresses a comma-separated list of integer pages to a hyphenated range
     * form, e.g. {@code "1,2,3,5,6"} becomes {@code "1-3,5,6"}. Non-numeric
     * input is returned unchanged so Roman-numeral or front-matter pages from
     * the source document are preserved verbatim.
     *
     * @param individualPageNumbers the comma-separated input
     * @return the compressed range string, or {@link #NOT_AVAILABLE} when empty
     */
    static String compressPageRange(final String individualPageNumbers) {
        if (StringUtil.isNullOrEmpty(individualPageNumbers) || NOT_AVAILABLE.equals(individualPageNumbers)) {
            return NOT_AVAILABLE;
        }

        final List<Integer> pages = new ArrayList<>();
        for (final String token : individualPageNumbers.split(",")) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                pages.add(Integer.parseInt(trimmed));
            } catch (final NumberFormatException e) {
                return individualPageNumbers;
            }
        }
        if (pages.isEmpty()) {
            return NOT_AVAILABLE;
        }

        final StringBuilder out = new StringBuilder();
        int rangeStart = pages.get(0);
        int prev = rangeStart;
        for (int i = 1; i < pages.size(); i++) {
            final int curr = pages.get(i);
            if (curr != prev + 1) {
                appendRange(out, rangeStart, prev);
                rangeStart = curr;
            }
            prev = curr;
        }
        appendRange(out, rangeStart, prev);
        return out.toString();
    }

    private static void appendRange(final StringBuilder out, final int start, final int end) {
        if (out.length() > 0) {
            out.append(',');
        }
        if (start == end) {
            out.append(start);
        } else if (end == start + 1) {
            out.append(start).append(',').append(end);
        } else {
            out.append(start).append('-').append(end);
        }
    }

    /** Internal record capturing the position and payload of a matched {@code <FACT_MAP_JSON>} tag. */
    private record TagLocation(int start, int end, String payload) { }

    /** Text after the removal of unresolvable markers, and how many marker occurrences were removed. */
    private record MarkerRemoval(String text, int removed) { }
}
