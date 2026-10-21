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
 * Turns raw LLM output into the user-visible answer: parses the {@code <FACT_MAP_JSON>} block,
 * validates its entries ({@link CitationEntryValidator}) and replaces the inline {@code [N]}
 * markers of valid entries with citations rendered from retrieval, held as
 * {@link RenderedCitations} sentinels until the end. Markers without a valid entry are removed.
 *
 * <p>Tolerated LLM drift:
 * <ul>
 *   <li>the last block wins, so an echoed example tag is ignored; prose either side is kept</li>
 *   <li>{@code ```json} fences around the payload; tags in any case or with inner whitespace</li>
 *   <li>joined ids: {@code [1, 2]} becomes {@code [1][2]}</li>
 *   <li>drift forms: {@code [N p.X]}, {@code [N:X]}, {@code [^N]}, {@code [ N ]},
 *       {@code [Source N]}, {@code [doc N]}, {@code [Citation N]}, {@code [Ref N]}</li>
 *   <li>adjacent markers of the same document ({@code [2][3]}) merge into one citation</li>
 *   <li>no usable block (counter-loop, reasoning-token truncation): every bare marker is stripped</li>
 * </ul>
 */
public class CitationProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CitationProcessor.class);

    private static final Pattern JSON_TAG_PATTERN = Pattern.compile(
            "<\\s*" + FACT_MAP_ATTRIBUTE_KEY + "\\s*>(.*?)<\\s*/\\s*" + FACT_MAP_ATTRIBUTE_KEY + "\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE_OPEN =
            Pattern.compile("\\A\\s*```(?:json|JSON)?\\s*", Pattern.DOTALL);
    private static final Pattern CODE_FENCE_CLOSE =
            Pattern.compile("\\s*```\\s*\\z", Pattern.DOTALL);

    private static final Pattern JOINED_IDS_PATTERN =
            Pattern.compile("\\[(" + NUMERIC_ID + "(?:[ \\t]{0,3}[,;][ \\t]{0,3}" + NUMERIC_ID + "){1,50})\\]");

    private static final Pattern JOINED_IDS_SEPARATOR = Pattern.compile("\\s*[,;]\\s*");

    private static final Pattern BARE_MARKER_PATTERN = Pattern.compile("\\[(" + NUMERIC_ID + ")\\]");

    /** One or more bare markers, at most three spaces or tabs apart; a newline ends the run, so
     *  citations of separate list items never merge. */
    private static final Pattern MARKER_RUN_PATTERN =
            Pattern.compile("\\[" + NUMERIC_ID + "\\](?:[ \\t]{0,3}\\[" + NUMERIC_ID + "\\]){0,50}");

    /** A bare or drift-form placeholder; group 1 is the citation id. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "(?i)\\[\\^?(?:(?:Source|doc|Citation|Ref)[\\s:]{1,5})?\\s{0,3}(" + NUMERIC_ID
                    + ")(?:[\\s,;:][^\\[\\]]*)?\\]");

    private static final int CATASTROPHIC_MARKER_THRESHOLD = 100;

    private static final String CITATION_FORMAT = "::(Source: [%s], Pages %s|%s|documentId=%s)";

    private final ObjectMapper objectMapper = getObjectMapper();

    /**
     * The result the citation guard in {@link ResponseGenerationService} decides on.
     *
     * @param emptyFactMap    the block parsed to {@code []}; judged on the original list, so an
     *                        answer whose every entry is invalid is not a refusal
     * @param inlineMarkers   bare markers the model wrote, after joined-id expansion
     * @param strippedMarkers markers removed without being rendered
     * @param validation      {@link ValidationCounts#NONE} when there was no parseable block
     */
    public record CitationOutcome(String formattedText, boolean jsonBlockPresent,
                                  boolean emptyFactMap, int inlineMarkers,
                                  int renderedCitations, int strippedMarkers,
                                  ValidationCounts validation) {

        public CitationOutcome {
            validation = validation == null ? ValidationCounts.NONE : validation;
        }

        public int invalidEntries() {
            return validation.invalidEntries();
        }

        public int trimmedPages() {
            return validation.trimmedPages();
        }
    }

    /** @param scope what the model was shown; {@code null} means no entry can be valid */
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

    private static CitationOutcome withoutCitations(final String answerText, final boolean jsonBlockPresent) {
        final int markers = countBareMarkers(answerText);
        warnStripped(markers, markers, "came with no usable <" + FACT_MAP_ATTRIBUTE_KEY
                + "> block (likely reasoning-token truncation)");
        return new CitationOutcome(BARE_MARKER_PATTERN.matcher(answerText).replaceAll("").trim(),
                jsonBlockPresent, false, markers, 0, markers, ValidationCounts.NONE);
    }

    /**
     * Three passes, in this order: drop the drift forms of dropped ids (so they cannot split a
     * run); render each run of bare markers, minus ids without a valid entry, as one citation per
     * same-document group; render the remaining drift forms of resolved ids.
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

    private static String format(final CitableDocument document, final SortedSet<Integer> pages) {
        final String individual = pages.stream().map(String::valueOf).collect(Collectors.joining(","));
        return String.format(CITATION_FORMAT, document.displayedFileName(), compressPageRange(pages),
                individual, document.documentId());
    }

    /** Warns on a catastrophic count, else on {@code reported} markers only: validation drops are
     *  expected and reported by the guard. */
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

    /** Ascending pages to range form: {@code 1,2,3,5,6} becomes {@code 1-3,5,6} (pairs stay pairs). */
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

    private record TagLocation(int start, int end, String payload) { }
}
