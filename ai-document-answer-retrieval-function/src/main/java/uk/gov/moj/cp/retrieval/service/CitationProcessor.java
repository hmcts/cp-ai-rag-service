package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.CITATION_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.FACT_MAP_ATTRIBUTE_KEY;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.INDIVIDUAL_PAGE_NUMBERS;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.PAGE_NUMBERS;

import uk.gov.moj.cp.ai.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Extracts the {@code <FACT_MAP_JSON>} payload, substitutes the inline
 * {@code [N]} placeholders with the human-readable source string, and applies a
 * series of tolerance rules covering the LLM output drift modes observed in
 * production with GPT-4o and GPT-4.1.
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
 *       <strong>same</strong> documentId is collapsed into a single formatted
 *       citation carrying the sorted, de-duplicated union of the entries'
 *       pages. Adjacent placeholders citing <em>different</em> documents are
 *       legitimate multi-document support and are left separate. The merge is
 *       positional: JSON entries are never removed, so the same id appearing
 *       non-adjacently elsewhere still substitutes normally.</li>
 *   <li>Strips <em>unresolved</em> bare {@code [N]} markers — any inline
 *       placeholder left over once substitution has run, because it had no
 *       matching JSON entry, the {@code <FACT_MAP_JSON>} block was absent, or the
 *       JSON failed to parse. This covers two failure modes: the GPT-4o
 *       catastrophic counter-loop (output flooded with {@code [1][2]...[N]}) and
 *       the GPT-5.1 reasoning-token truncation, where the response is cut off
 *       before the closing tag so the citation block is lost entirely. In both
 *       cases the user would otherwise see naked, meaningless brackets.</li>
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

    /** Above this many surviving {@code [N]} markers, the output is treated as catastrophic. */
    private static final int CATASTROPHIC_BRACKET_THRESHOLD = 100;

    /** Placeholder regex template; the {@code %s} is substituted with the citation id. */
    private static final String PLACEHOLDER_REGEX_TEMPLATE =
            "(?i)\\[\\^?(?:(?:Source|doc|Citation|Ref)[\\s:]+)?\\s*%s(?:[\\s,;:][^\\[\\]]*)?\\]";

    /** The user-visible citation format. */
    private static final String CITATION_FORMAT = "::(Source: [%s], Pages %s|%s|documentId=%s)";

    private static final String UNKNOWN_FILE = "UNKNOWN_FILE";
    private static final String UNKNOWN_ID = "UNKNOWN_ID";
    private static final String NOT_AVAILABLE = "N/A";

    private final ObjectMapper objectMapper = getObjectMapper();

    /**
     * Executes the post-processing pipeline: extracts structured citations and substitutes
     * the inline numerical placeholders with the fully formatted citation string.
     *
     * @param rawLlmOutput the raw text output containing the narrative and the embedded JSON
     * @return the clean, fully cited response text, or an empty string if the input is empty
     */
    public String processAndFormatCitations(final String rawLlmOutput) {
        if (StringUtil.isNullOrEmpty(rawLlmOutput)) {
            return "";
        }

        final TagLocation lastTag = findLastJsonTag(rawLlmOutput);
        if (lastTag == null) {
            LOGGER.warn("Raw output missing required <{}> tags; cannot format citations.", FACT_MAP_ATTRIBUTE_KEY);
            return stripUnresolvedCitationMarkers(rawLlmOutput, true).trim();
        }

        final String answerBeforeTag = rawLlmOutput.substring(0, lastTag.start);
        final String answerAfterTag = rawLlmOutput.substring(lastTag.end);
        String answerText = normaliseJoinedIds((answerBeforeTag + " " + answerAfterTag).trim());

        final String jsonPayload = stripCodeFences(lastTag.payload).trim();

        try {
            final List<Map<String, Object>> citations =
                    objectMapper.readValue(jsonPayload, new TypeReference<>() { });
            answerText = mergeSameDocumentRuns(answerText, citations);
            for (final Map<String, Object> citation : citations) {
                answerText = substituteCitation(answerText, citation);
            }
            return stripUnresolvedCitationMarkers(answerText, false).trim();
        } catch (final Exception e) {
            LOGGER.warn("Failed to parse citation JSON or substitute placeholders; returning narrative without citations.", e);
            return stripUnresolvedCitationMarkers(answerText, true).trim();
        }
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

    private static String substituteCitation(final String answerText, final Map<String, Object> citation) {
        final String citationIdStr = stringifyJsonValue(citation.get(CITATION_ID));
        if (StringUtil.isNullOrEmpty(citationIdStr)) {
            return answerText;
        }

        final String individual = stringifyJsonValue(citation.getOrDefault(INDIVIDUAL_PAGE_NUMBERS, NOT_AVAILABLE));
        final String compressed = resolveCompressedPages(citation, individual);

        final String formatted = formatCitation(
                stringifyJsonValue(citation.getOrDefault(DOCUMENT_FILE_NAME, UNKNOWN_FILE)),
                compressed,
                individual,
                stringifyJsonValue(citation.getOrDefault(DOCUMENT_ID, UNKNOWN_ID)));

        final String placeholderRegex = String.format(PLACEHOLDER_REGEX_TEMPLATE, Pattern.quote(citationIdStr));
        return answerText.replaceAll(placeholderRegex, Matcher.quoteReplacement(formatted));
    }

    private static String formatCitation(final String filename, final String compressed,
                                         final String individual, final String documentId) {
        return String.format(CITATION_FORMAT, filename, compressed, individual, documentId);
    }

    /**
     * Collapses each maximal run of adjacent bare {@code [N]} markers whose citationIds resolve
     * to the same documentId into a single formatted citation carrying the union of the entries'
     * pages. The rewrite is positional — only the matched run region changes and no JSON entry
     * is removed, so an id reused non-adjacently elsewhere still substitutes normally. Runs (or
     * portions of runs) citing different documents, or ids without a JSON entry, are re-emitted
     * verbatim for the ordinary substitution and unresolved-marker stripping to handle.
     */
    private static String mergeSameDocumentRuns(final String answerText, final List<Map<String, Object>> citations) {
        final Map<String, Map<String, Object>> idToEntry = new LinkedHashMap<>();
        for (final Map<String, Object> citation : citations) {
            final String id = stringifyJsonValue(citation.get(CITATION_ID));
            if (!StringUtil.isNullOrEmpty(id)) {
                idToEntry.putIfAbsent(id, citation);
            }
        }
        if (idToEntry.isEmpty()) {
            return answerText;
        }

        final Matcher run = MARKER_RUN_PATTERN.matcher(answerText);
        final StringBuilder out = new StringBuilder(answerText.length());
        int last = 0;
        while (run.find()) {
            out.append(answerText, last, run.start());
            out.append(buildRunReplacement(run.group(), idToEntry));
            last = run.end();
        }
        return out.append(answerText, last, answerText.length()).toString();
    }

    /**
     * Rewrites one adjacent-marker run: maximal consecutive ids sharing a non-null documentId
     * become one merged citation; every other id is re-emitted as its original {@code [N]}.
     */
    private static String buildRunReplacement(final String runText, final Map<String, Map<String, Object>> idToEntry) {
        final List<String> ids = new ArrayList<>();
        final Matcher marker = SINGLE_MARKER_PATTERN.matcher(runText);
        while (marker.find()) {
            ids.add(marker.group(1));
        }

        final StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < ids.size()) {
            final String documentId = documentIdOf(idToEntry.get(ids.get(i)));
            int j = i + 1;
            while (documentId != null && j < ids.size()
                    && documentId.equals(documentIdOf(idToEntry.get(ids.get(j))))) {
                j++;
            }
            appendGroup(out, ids.subList(i, j), documentId, idToEntry);
            i = j;
        }
        return out.toString();
    }

    private static void appendGroup(final StringBuilder out, final List<String> groupIds, final String documentId,
                                    final Map<String, Map<String, Object>> idToEntry) {
        if (groupIds.size() < 2) {
            out.append('[').append(groupIds.get(0)).append(']');
            return;
        }
        out.append(formatMergedCitation(groupIds, documentId, idToEntry));
        LOGGER.info("Merged {} adjacent same-document citation markers {} into one citation for documentId={}",
                groupIds.size(), groupIds, documentId);
    }

    private static String formatMergedCitation(final List<String> groupIds, final String documentId,
                                               final Map<String, Map<String, Object>> idToEntry) {
        final List<Map<String, Object>> entries = new LinkedHashSet<>(groupIds).stream()
                .map(idToEntry::get)
                .toList();
        // The union invalidates any single entry's LLM-provided compressed "pageNumbers" string,
        // so the compressed form is always recomputed from the unioned pages.
        final String individual = unionPages(entries);
        return formatCitation(
                stringifyJsonValue(entries.get(0).getOrDefault(DOCUMENT_FILE_NAME, UNKNOWN_FILE)),
                compressPageRange(individual),
                individual,
                documentId);
    }

    /**
     * Unions the {@code individualPageNumbers} of the given entries: numeric pages de-duplicated
     * and sorted ascending, then non-numeric tokens (e.g. Roman-numeral front-matter pages) in
     * first-seen order.
     */
    private static String unionPages(final List<Map<String, Object>> entries) {
        final TreeSet<Integer> numeric = new TreeSet<>();
        final LinkedHashSet<String> nonNumeric = new LinkedHashSet<>();
        for (final Map<String, Object> entry : entries) {
            final String pages = stringifyJsonValue(entry.get(INDIVIDUAL_PAGE_NUMBERS));
            if (StringUtil.isNullOrEmpty(pages)) {
                continue;
            }
            for (final String token : pages.split(",")) {
                addPageToken(token.trim(), numeric, nonNumeric);
            }
        }
        if (numeric.isEmpty() && nonNumeric.isEmpty()) {
            return NOT_AVAILABLE;
        }
        final StringBuilder out = new StringBuilder();
        numeric.forEach(page -> appendToken(out, String.valueOf(page)));
        nonNumeric.forEach(token -> appendToken(out, token));
        return out.toString();
    }

    private static void addPageToken(final String token, final TreeSet<Integer> numeric,
                                     final LinkedHashSet<String> nonNumeric) {
        if (token.isEmpty() || NOT_AVAILABLE.equals(token)) {
            return;
        }
        try {
            numeric.add(Integer.parseInt(token));
        } catch (final NumberFormatException e) {
            nonNumeric.add(token);
        }
    }

    private static void appendToken(final StringBuilder out, final String token) {
        if (out.length() > 0) {
            out.append(',');
        }
        out.append(token);
    }

    private static String documentIdOf(final Map<String, Object> entry) {
        return entry == null ? null : stringifyJsonValue(entry.get(DOCUMENT_ID));
    }

    /**
     * Returns the compressed page range string. Honours the LLM-provided
     * {@code pageNumbers} field when present and non-empty; otherwise derives
     * it from {@code individualPageNumbers}.
     */
    private static String resolveCompressedPages(final Map<String, Object> citation, final String individual) {
        final String provided = stringifyJsonValue(citation.get(PAGE_NUMBERS));
        if (!StringUtil.isNullOrEmpty(provided) && !NOT_AVAILABLE.equals(provided)) {
            return provided;
        }
        return compressPageRange(individual);
    }

    private static String stringifyJsonValue(final Object value) {
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
     * JSON failed to parse, or the id had no matching JSON entry — in all of which
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
}
