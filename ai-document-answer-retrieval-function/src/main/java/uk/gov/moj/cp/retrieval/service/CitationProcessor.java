package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.FACT_MAP_ATTRIBUTE_KEY;
import static uk.gov.moj.cp.retrieval.service.CitationEntryValidator.NUMERIC_ID;

import uk.gov.moj.cp.ai.util.StringUtil;
import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.model.CitationScope.CitableDocument;
import uk.gov.moj.cp.retrieval.model.ValidationCounts;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ResolvedCitation;
import uk.gov.moj.cp.retrieval.service.CitationEntryValidator.ValidationResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.MatchResult;
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
 * model was actually shown ({@link CitationEntryValidator} owns the rules), and replaces the
 * inline {@code [N]} placeholders of the valid entries with the human-readable source string,
 * rendered from retrieval — never from the model's copies. Placeholders of ids with no valid
 * entry are removed. Citation ids are numeric; anything else in brackets is prose.
 *
 * <p>Tolerance rules implemented, covering the LLM output drift observed with GPT-4o, GPT-4.1
 * and GPT-5.1:
 * <ul>
 *   <li>Uses the <strong>last</strong> {@code <FACT_MAP_JSON>} block, so an echoed example tag
 *       does not discard the real answer, and keeps prose on both sides of the tag.</li>
 *   <li>Strips markdown {@code ```json} fences around the payload; accepts the tags in any
 *       letter case and with whitespace inside the angle brackets.</li>
 *   <li>Expands joined ids such as {@code [1, 2]} into {@code [1][2]}.</li>
 *   <li>Accepts drift forms of a placeholder: {@code [N p.X]}, {@code [N, p.X]}, {@code [N:X]},
 *       {@code [^N]}, {@code [ N ]}, {@code [Source N]}, {@code [doc N]}, {@code [Citation N]},
 *       {@code [Ref N]} (label case-insensitive).</li>
 *   <li>Merges a run of adjacent bare markers resolving to the <strong>same</strong> retrieved
 *       document (e.g. {@code [2][3]}, gaps of at most a few spaces, never across a newline) into
 *       one citation with the union of their pages; adjacent markers of different documents stay
 *       separate. Markers without a valid entry are removed from the run first, so they never
 *       split it.</li>
 *   <li>Strips every bare {@code [N]} marker when there is no usable block — the GPT-4o
 *       counter-loop ({@code [1][2]...[N]}) and GPT-5.1 reasoning-token truncation, where the
 *       block never arrives — so the reader never sees naked brackets.</li>
 * </ul>
 *
 * <p>Rendered citations stay behind opaque sentinels ({@link RenderedCitations}) until the final
 * step, so a filename that looks like a marker or a citation is never re-processed or counted.
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

    /** Comma- or semicolon-joined citation ids in a single bracket, e.g. {@code [1, 2]}. */
    private static final Pattern JOINED_IDS_PATTERN =
            Pattern.compile("\\[(" + NUMERIC_ID + "(?:[ \\t]{0,3}[,;][ \\t]{0,3}" + NUMERIC_ID + "){1,50})\\]");

    /** Splitter for the joined-id pattern groups. */
    private static final Pattern JOINED_IDS_SEPARATOR = Pattern.compile("\\s*[,;]\\s*");

    /** A bare {@code [N]} marker; group 1 is the citation id. */
    private static final Pattern BARE_MARKER_PATTERN = Pattern.compile("\\[(" + NUMERIC_ID + ")\\]");

    /** A run of one or more bare markers separated by at most three spaces or tabs (a newline ends
     *  the run — citations of separate list items must not merge). Bounded quantifiers, each
     *  repetition anchored on a literal '[' (S5852). */
    private static final Pattern MARKER_RUN_PATTERN =
            Pattern.compile("\\[" + NUMERIC_ID + "\\](?:[ \\t]{0,3}\\[" + NUMERIC_ID + "\\]){0,50}");

    /** Any placeholder, bare or drift form; group 1 is the citation id. The label separator and
     *  leading whitespace are bounded so they cannot backtrack against each other (S5852). */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "(?i)\\[\\^?(?:(?:Source|doc|Citation|Ref)[\\s:]{1,5})?\\s{0,3}(" + NUMERIC_ID
                    + ")(?:[\\s,;:][^\\[\\]]*)?\\]");

    /** Above this many markers removed at once, the output is treated as catastrophic. */
    private static final int CATASTROPHIC_MARKER_THRESHOLD = 100;

    /** The user-visible citation format. */
    private static final String CITATION_FORMAT = "::(Source: [%s], Pages %s|%s|documentId=%s)";

    private final ObjectMapper objectMapper = getObjectMapper();

    /**
     * The outcome of citation post-processing, consumed by the citation guard in
     * {@code ResponseGenerationService} to decide whether an answer may progress.
     *
     * @param formattedText      the user-visible answer
     * @param jsonBlockPresent   a complete {@code <FACT_MAP_JSON>} block was found
     * @param emptyFactMap       the block parsed to an empty array (deliberate no-evidence refusal);
     *                           computed on the original list, so "every entry invalid" is never a refusal
     * @param inlineMarkers      bare {@code [N]} markers the model wrote (after joined-id normalisation)
     * @param renderedCitations  citations rendered into the formatted text
     * @param strippedMarkers    markers removed without being rendered (ids with no valid entry,
     *                           including drift forms of dropped ids; every marker when no block)
     * @param validation         entry-level validation counts; {@link ValidationCounts#NONE} when
     *                           there was no parseable block
     */
    public record CitationOutcome(String formattedText, boolean jsonBlockPresent,
                                  boolean emptyFactMap, int inlineMarkers,
                                  int renderedCitations, int strippedMarkers,
                                  ValidationCounts validation) {

        public CitationOutcome {
            validation = validation == null ? ValidationCounts.NONE : validation;
        }

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
     * Extracts the citations, validates them against {@code scope} and renders the valid ones.
     *
     * @param rawLlmOutput the raw text output containing the narrative and the embedded JSON
     * @param scope        what the model was shown; {@code null} is treated as {@link CitationScope#empty()}
     *                     (every entry invalid — validation is never skipped)
     */
    public CitationOutcome processCitations(final String rawLlmOutput, final CitationScope scope) {
        if (StringUtil.isNullOrEmpty(rawLlmOutput)) {
            return new CitationOutcome("", false, false, 0, 0, 0, ValidationCounts.NONE);
        }
        final String raw = RenderedCitations.withoutSentinelDelimiter(rawLlmOutput);

        final TagLocation lastTag = findLastJsonTag(raw);
        if (lastTag == null) {
            LOGGER.warn("Raw output missing required <{}> tags; cannot format citations.", FACT_MAP_ATTRIBUTE_KEY);
            return withoutCitations(normaliseJoinedIds(raw), false);
        }

        final String answerText = normaliseJoinedIds(
                (raw.substring(0, lastTag.start) + " " + raw.substring(lastTag.end)).trim());
        try {
            final List<Map<String, Object>> citations =
                    objectMapper.readValue(stripCodeFences(lastTag.payload).trim(), new TypeReference<>() { });
            if (citations == null) {
                LOGGER.warn("Citation JSON payload is null; returning narrative without citations.");
                return withoutCitations(answerText, true);
            }
            return applyCitations(answerText, citations, CitationEntryValidator.validate(citations, scope));
        } catch (final Exception e) {
            LOGGER.warn("Failed to parse citation JSON or substitute placeholders; returning narrative without citations.", e);
            return withoutCitations(answerText, true);
        }
    }

    /** No usable citation JSON: every bare marker is stripped and nothing renders. */
    private static CitationOutcome withoutCitations(final String answerText, final boolean jsonBlockPresent) {
        final int markers = countBareMarkers(answerText);
        warnStripped(markers, markers, "came with no usable <" + FACT_MAP_ATTRIBUTE_KEY
                + "> block (likely reasoning-token truncation)");
        return new CitationOutcome(BARE_MARKER_PATTERN.matcher(answerText).replaceAll("").trim(),
                jsonBlockPresent, false, markers, 0, markers, ValidationCounts.NONE);
    }

    /**
     * Renders the validated citations in three passes, then expands them:
     * <ol>
     *   <li>drift forms of dropped ids are removed, so they cannot break a run;</li>
     *   <li>each run of bare markers loses the ids with no valid entry, and its remaining ids
     *       become one citation per consecutive same-document group;</li>
     *   <li>the remaining drift forms of resolved ids become their citation.</li>
     * </ol>
     */
    private static CitationOutcome applyCitations(final String answerText, final List<Map<String, Object>> citations,
                                                  final ValidationResult validation) {
        final Map<String, ResolvedCitation> resolvedById = validation.resolvedById();
        final Set<String> droppedIds = validation.droppedIds();
        final RenderedCitations rendered = new RenderedCitations();
        final Removed removed = new Removed();

        final String withoutDroppedDrift = PLACEHOLDER_PATTERN.matcher(answerText).replaceAll(match -> {
            if (isBare(match) || !droppedIds.contains(match.group(1))) {
                return Matcher.quoteReplacement(match.group());
            }
            removed.driftForms++;
            return "";
        });

        final String withRuns = MARKER_RUN_PATTERN.matcher(withoutDroppedDrift).replaceAll(run -> {
            final List<String> ids = new ArrayList<>();
            final Matcher marker = BARE_MARKER_PATTERN.matcher(run.group());
            while (marker.find()) {
                final String id = marker.group(1);
                if (resolvedById.containsKey(id)) {
                    ids.add(id);
                } else {
                    removed.bareMarkers++;
                    removed.orphans += droppedIds.contains(id) ? 0 : 1;
                }
            }
            return Matcher.quoteReplacement(renderRun(ids, resolvedById, rendered));
        });
        warnStripped(removed.bareMarkers, removed.orphans, "had no matching JSON citation entry");

        final String withSentinels = PLACEHOLDER_PATTERN.matcher(withRuns).replaceAll(match -> {
            final ResolvedCitation resolved = resolvedById.get(match.group(1));
            return Matcher.quoteReplacement(resolved == null ? match.group() : rendered.register(render(resolved)));
        }).trim();

        return new CitationOutcome(rendered.expand(withSentinels), true, citations.isEmpty(),
                countBareMarkers(answerText), rendered.count(withSentinels),
                removed.driftForms + removed.bareMarkers, validation.counts());
    }

    /** Markers removed without being rendered, while one answer is processed. */
    private static final class Removed {
        private int driftForms;
        private int bareMarkers;
        private int orphans;
    }

    private static boolean isBare(final MatchResult placeholder) {
        return placeholder.group().length() == placeholder.group(1).length() + 2;
    }

    /** One sentinel per maximal group of consecutive ids resolving to the same document. */
    private static String renderRun(final List<String> ids, final Map<String, ResolvedCitation> resolvedById,
                                    final RenderedCitations rendered) {
        final StringBuilder out = new StringBuilder();
        int start = 0;
        while (start < ids.size()) {
            final CitableDocument document = resolvedById.get(ids.get(start)).document();
            final SortedSet<Integer> pages = new TreeSet<>();
            int end = start;
            while (end < ids.size() && resolvedById.get(ids.get(end)).document().documentId()
                    .equals(document.documentId())) {
                pages.addAll(resolvedById.get(ids.get(end)).pages());
                end++;
            }
            if (end - start > 1) {
                LOGGER.info("Merged {} adjacent same-document citation markers {} into one citation",
                        end - start, ids.subList(start, end));
            }
            out.append(rendered.register(format(document, pages)));
            start = end;
        }
        return out.toString();
    }

    private static String render(final ResolvedCitation resolved) {
        return format(resolved.document(), resolved.pages());
    }

    /** Renders one citation entirely from retrieval: displayed filename, documentId and pages. */
    private static String format(final CitableDocument document, final SortedSet<Integer> pages) {
        final String individual = pages.stream().map(String::valueOf).collect(Collectors.joining(","));
        return String.format(CITATION_FORMAT, document.displayedFileName(), compressPageRange(pages),
                individual, document.documentId());
    }

    /**
     * Logs markers removed without being rendered: a catastrophic count (the counter-loop) always,
     * otherwise only the {@code reported} ones, with {@code reason}. Markers of entries that failed
     * validation are expected and counted by the caller, so they are not reported on their own.
     */
    private static void warnStripped(final int removed, final int reported, final String reason) {
        if (removed > CATASTROPHIC_MARKER_THRESHOLD) {
            LOGGER.warn("Catastrophic citation pathology: {} unresolved [N] markers; stripping all.", removed);
        } else if (reported > 0) {
            LOGGER.warn("{} inline [N] marker(s) {}; stripping unresolved markers.", reported, reason);
        }
    }

    private static int countBareMarkers(final String text) {
        return (int) BARE_MARKER_PATTERN.matcher(text).results().count();
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
            final StringBuilder out = new StringBuilder();
            for (final String id : JOINED_IDS_SEPARATOR.split(match.group(1))) {
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
     * Compresses ascending pages to a hyphenated range form, e.g. {1,2,3,5,6} becomes
     * {@code "1-3,5,6"}; a pair of consecutive pages stays a pair ({@code "5,6"}).
     */
    static String compressPageRange(final Collection<Integer> sortedPages) {
        final StringBuilder out = new StringBuilder();
        Integer rangeStart = null;
        Integer prev = null;
        for (final Integer page : sortedPages) {
            if (prev != null && page != prev + 1) {
                appendRange(out, rangeStart, prev);
                rangeStart = null;
            }
            rangeStart = rangeStart == null ? page : rangeStart;
            prev = page;
        }
        if (prev != null) {
            appendRange(out, rangeStart, prev);
        }
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
}
