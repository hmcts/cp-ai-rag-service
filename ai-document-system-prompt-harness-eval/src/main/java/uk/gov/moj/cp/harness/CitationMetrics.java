package uk.gov.moj.cp.harness;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.CitationProcessor;

/**
 * Pure, stateless derivation of citation-format compliance metrics from a single LLM response —
 * the numbers every evaluation report headlines (prose length, cited pages, rendered/stripped
 * citations, same-document stacking, "substantive but uncited").
 *
 * <p>Extracted from {@link TestHarness} so this logic is unit-testable without the harness's
 * env-bound static initialisation. It performs no environment access and no I/O:
 * {@link CitationProcessor} is deterministic in-process string processing, so {@link #compute}
 * is a pure function of its input.
 */
final class CitationMetrics {

    /** Words of prose at/above which an answer counts as substantive (refusals are far shorter). */
    static final int SUBSTANTIVE_PROSE_WORDS = 50;

    private static final CitationProcessor OUTCOME_PROCESSOR = new CitationProcessor();

    private static final Pattern BARE_BRACKET = Pattern.compile("\\[(\\d+)\\]");
    // Bounded repetition ({0,N}) on the negated classes: citation markers and page lists are short,
    // and a bounded quantifier cannot backtrack super-linearly — this satisfies SonarQube's S5852
    // (regex-DoS) check, which a possessive quantifier did not.
    // Includes labelled forms ([Source 1], [Ref 2] …) that CitationProcessor's tolerant regex
    // substitutes but a digit-first pattern would miss (observed blind spot).
    private static final Pattern ANY_BRACKET_CITATION = Pattern.compile(
            "(?i)\\[\\^?(?:(?:Source|doc|Citation|Ref)[\\s:]{1,3})?\\d[^\\[\\]]{0,40}\\]");
    /** Any bracketed token — used to strip all citation markers when measuring prose length. */
    private static final Pattern BRACKET_TOKEN = Pattern.compile("\\[[^\\[\\]]{0,64}\\]");
    private static final Pattern JSON_CITATION_ID = Pattern.compile("\"citationId\"\\s*:\\s*(\\d+)");
    /** Captures each individualPageNumbers value, to count how many source pages the answer cites. */
    private static final Pattern INDIVIDUAL_PAGES = Pattern.compile("\"individualPageNumbers\"\\s*:\\s*\"([^\"]{0,200})\"");
    /** A maximal run of >=2 adjacent bare [N] markers, separated by at most a few horizontal
     *  whitespace chars. Every quantifier is bounded and each repetition is anchored on a literal
     *  '[' — no super-linear backtracking (S5852). */
    private static final Pattern MARKER_RUN = Pattern.compile("\\[\\d{1,4}\\](?:[ \\t]{0,3}\\[\\d{1,4}\\]){1,50}");
    /** One JSON object literal inside a FACT_MAP_JSON payload (objects are flat — no nesting). */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]{0,600}\\}");
    /** Quote-tolerant citationId, for the id→documentId map: models emit both 1 and "1". */
    private static final Pattern OBJ_CITATION_ID = Pattern.compile("\"citationId\"\\s*:\\s*\"?(\\d{1,6})\"?");
    private static final Pattern OBJ_DOCUMENT_ID = Pattern.compile("\"documentId\"\\s*:\\s*\"([^\"]{0,80})\"");
    /** Literal citation-block tags (the prompt mandates these exact tags). Matched case-insensitively
     *  by index scan rather than a regex, to avoid any backtracking over the block body. */
    private static final String FACT_MAP_OPEN = "<FACT_MAP_JSON>";
    private static final String FACT_MAP_CLOSE = "</FACT_MAP_JSON>";

    private CitationMetrics() {
    }

    /**
     * Citation-format compliance metrics for one response. {@code rawInlineMarkers}/{@code inlineIds}
     * are the bare {@code [N]} markers; {@code rawDriftMarkers} is the excess of any-bracket-citation
     * matches over bare ones; {@code jsonEntries}/{@code jsonIds} come from the FACT_MAP_JSON block;
     * {@code proseChars}/{@code proseWords} measure the narrative with the JSON block and every inline
     * bracket marker removed, so verbosity is comparable across models independently of citation count.
     */
    record Compliance(int rawInlineMarkers, Set<Integer> inlineIds, int rawDriftMarkers,
                      int jsonEntries, Set<Integer> jsonIds,
                      boolean inlineSubsetOfJson, boolean processorSubstituted,
                      boolean jsonBlockPresent, int proseChars, int proseWords, int citedPages,
                      int sameDocStackedRuns, int renderedCitations, int strippedMarkers,
                      boolean uncitedSubstantive) {
    }

    /**
     * Extract citation-format compliance metrics from one LLM response. Compares:
     * inline {@code [N]} markers (bare) vs any-bracket-citation matches (difference = drift),
     * JSON citationId values, whether every inline id appears in the JSON, whether
     * {@link CitationProcessor} actually substituted, and whether a parseable JSON block exists.
     */
    static Compliance compute(final LlmResponse response) {
        final String raw = response.rawLlmResponse() == null ? "" : response.rawLlmResponse();
        final String formatted = response.formattedLlmResponse() == null ? "" : response.formattedLlmResponse();

        final boolean jsonBlockPresent = hasFactMapBlock(raw);

        // Strip the FACT_MAP_JSON block(s) before counting inline markers — example
        // placeholders inside the JSON should not pollute the inline-marker count.
        final String rawWithoutJson = stripFactMapBlocks(raw);

        final Set<Integer> inlineIds = new TreeSet<>();
        int bareCount = 0;
        final Matcher bare = BARE_BRACKET.matcher(rawWithoutJson);
        while (bare.find()) {
            bareCount++;
            inlineIds.add(Integer.parseInt(bare.group(1)));
        }
        int anyCount = 0;
        final Matcher any = ANY_BRACKET_CITATION.matcher(rawWithoutJson);
        while (any.find()) {
            anyCount++;
        }
        final int driftCount = Math.max(0, anyCount - bareCount);

        final Set<Integer> jsonIds = new TreeSet<>();
        int jsonEntryCount = 0;
        final Matcher json = JSON_CITATION_ID.matcher(raw);
        while (json.find()) {
            jsonEntryCount++;
            jsonIds.add(Integer.parseInt(json.group(1)));
        }

        final boolean inlineSubsetOfJson = !inlineIds.isEmpty() && jsonIds.containsAll(inlineIds);
        final boolean processorSubstituted = formatted.contains("::(Source");

        // Prose-only length: drop every bracket citation marker from the JSON-stripped
        // text and collapse whitespace, so the count reflects the narrative alone.
        final String prose = BRACKET_TOKEN.matcher(rawWithoutJson).replaceAll("")
                .replaceAll("\\s+", " ").trim();
        final int proseChars = prose.length();
        final int proseWords = prose.isEmpty() ? 0 : prose.split(" ").length;

        // Coverage proxy: total source pages cited across all JSON entries. Watched alongside
        // proseWords so we can tell padding-removal (words down, pages flat) from fact-loss
        // (words down, pages down) when tuning length.
        int citedPages = 0;
        final Matcher pagesMatcher = INDIVIDUAL_PAGES.matcher(raw);
        while (pagesMatcher.find()) {
            for (final String tok : pagesMatcher.group(1).split(",")) {
                if (!tok.trim().isEmpty()) {
                    citedPages++;
                }
            }
        }

        final int sameDocStackedRuns =
                countSameDocStackedRuns(rawWithoutJson, buildCitationIdToDocumentId(raw));

        // Same signal the production citation guard consumes: rendered/stripped counts and the
        // "substantive but uncited" flag (legitimate short refusals stay below the word floor).
        final CitationProcessor.CitationOutcome outcome = OUTCOME_PROCESSOR.processCitations(raw);
        final boolean uncitedSubstantive =
                proseWords >= SUBSTANTIVE_PROSE_WORDS && outcome.renderedCitations() == 0;

        return new Compliance(bareCount, inlineIds, driftCount, jsonEntryCount, jsonIds,
                inlineSubsetOfJson, processorSubstituted, jsonBlockPresent, proseChars, proseWords, citedPages,
                sameDocStackedRuns, outcome.renderedCitations(), outcome.strippedMarkers(), uncitedSubstantive);
    }

    /**
     * Maps citationId → documentId from every object literal inside the FACT_MAP_JSON block(s),
     * located by the same index scan as {@link #stripFactMapBlocks} (no regex over the full
     * response). First mapping wins; quote-tolerant on the citationId.
     */
    private static Map<Integer, String> buildCitationIdToDocumentId(final String raw) {
        final Map<Integer, String> idToDoc = new LinkedHashMap<>();
        final String open = FACT_MAP_OPEN.toLowerCase(Locale.ROOT);
        final String close = FACT_MAP_CLOSE.toLowerCase(Locale.ROOT);
        final String lower = raw.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            final int o = lower.indexOf(open, from);
            final int c = o < 0 ? -1 : lower.indexOf(close, o + open.length());
            if (o < 0 || c < 0) {
                return idToDoc;
            }
            final Matcher obj = JSON_OBJECT.matcher(raw.substring(o + open.length(), c));
            while (obj.find()) {
                addObjectMapping(obj.group(), idToDoc);
            }
            from = c + close.length();
        }
    }

    private static void addObjectMapping(final String objectLiteral, final Map<Integer, String> idToDoc) {
        final Matcher id = OBJ_CITATION_ID.matcher(objectLiteral);
        final Matcher doc = OBJ_DOCUMENT_ID.matcher(objectLiteral);
        if (id.find() && doc.find()) {
            idToDoc.putIfAbsent(Integer.parseInt(id.group(1)), doc.group(1));
        }
    }

    /**
     * Counts maximal adjacent {@code [N][M]…} runs in the raw answer where at least two
     * CONSECUTIVE ids resolve to the same documentId — the "same-document stacking" the
     * prompt tells the model to merge into a single citation entry. Adjacent ids from
     * different documents (legitimate multi-document support) do not count.
     */
    private static int countSameDocStackedRuns(final String rawWithoutJson, final Map<Integer, String> idToDoc) {
        int stackedRuns = 0;
        final Matcher run = MARKER_RUN.matcher(rawWithoutJson);
        while (run.find()) {
            if (runHasSameDocAdjacency(run.group(), idToDoc)) {
                stackedRuns++;
            }
        }
        return stackedRuns;
    }

    private static boolean runHasSameDocAdjacency(final String runText, final Map<Integer, String> idToDoc) {
        String prevDoc = null;
        final Matcher m = BARE_BRACKET.matcher(runText);
        while (m.find()) {
            final String doc = idToDoc.get(Integer.parseInt(m.group(1)));
            if (doc != null && doc.equals(prevDoc)) {
                return true;
            }
            prevDoc = doc;
        }
        return false;
    }

    /** True if {@code raw} contains a complete (open … close) FACT_MAP_JSON block, case-insensitively. */
    private static boolean hasFactMapBlock(final String raw) {
        final String lower = raw.toLowerCase(Locale.ROOT);
        final int open = lower.indexOf(FACT_MAP_OPEN.toLowerCase(Locale.ROOT));
        return open >= 0 && lower.indexOf(FACT_MAP_CLOSE.toLowerCase(Locale.ROOT), open + FACT_MAP_OPEN.length()) >= 0;
    }

    /**
     * Returns {@code raw} with every complete FACT_MAP_JSON block removed via an index scan (no regex
     * backtracking), so example placeholders inside the JSON don't pollute the inline-marker count.
     */
    private static String stripFactMapBlocks(final String raw) {
        final String open = FACT_MAP_OPEN.toLowerCase(Locale.ROOT);
        final String close = FACT_MAP_CLOSE.toLowerCase(Locale.ROOT);
        final String lower = raw.toLowerCase(Locale.ROOT);
        final StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            final int o = lower.indexOf(open, from);
            final int c = o < 0 ? -1 : lower.indexOf(close, o + open.length());
            if (o < 0 || c < 0) {
                return out.append(raw, from, raw.length()).toString();
            }
            out.append(raw, from, o);
            from = c + close.length();
        }
    }

    /** Citation-stripped prose of a raw response: FACT_MAP block(s) and every bracket token removed. */
    static String proseOf(final String raw) {
        final String withoutJson = stripFactMapBlocks(raw == null ? "" : raw);
        return BRACKET_TOKEN.matcher(withoutJson).replaceAll("").replaceAll("\\s+", " ").trim();
    }
}
