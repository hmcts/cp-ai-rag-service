package uk.gov.moj.cp.harness;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.EmbeddingService;
import uk.gov.moj.cp.ai.client.ChatServiceFactory;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.retrieval.exception.SearchServiceException;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.CitationProcessor;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;
import uk.gov.moj.cp.retrieval.service.UserInstructionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-model system-prompt evaluation harness for the answer-retrieval pipeline.
 *
 * <p>This exercise compares candidate answer-generation system prompts across the chat
 * models the service can run on (e.g. {@code gpt-4o} and {@code gpt-5.1}), to see how each
 * prompt holds up on citation behaviour, verbosity and source coverage. For every
 * (prompt × LLM × query) tuple it runs the full production pipeline — Azure embeddings →
 * AI Search (filtered by documentId) → {@link ChunkFormatterUtility} →
 * {@link ResponseGenerationService} → {@link CitationProcessor} — and aggregates the metrics.
 *
 * <p>What it exercises and reports:
 * <ul>
 *   <li><b>Real production path.</b> Builds the chat service via the production
 *       {@link ChatServiceFactory}, so the actual {@code isReasoningModel} branch runs
 *       (reasoning models such as gpt-5.1 omit {@code temperature}/{@code top_p} and apply
 *       {@code reasoning_effort}; gpt-4o gets {@code temperature=0}/{@code top_p=0}).</li>
 *   <li><b>Prompts under test.</b> Loaded from {@code src/main/resources/prompts/*.txt}
 *       ({@code v1-baseline-production} and {@code v2-baseline-with-improvements}); the query set
 *       is {@code src/main/resources/user-queries.json}.</li>
 *   <li><b>Metrics.</b> Per cell, across {@link #REPETITIONS} repeats: {@code ok}
 *       (answer generated), {@code jsonBlockPresent} (a parseable
 *       {@code <FACT_MAP_JSON>…</FACT_MAP_JSON>} block — isolates reasoning-token truncation
 *       on reasoning models from ordinary citation mismatches), {@code match} (every inline
 *       {@code [N]} resolves to a JSON entry), {@code subst} (the processor substituted a
 *       source), prose length (verbosity, citation-independent) and cited-page count
 *       (coverage). See {@link Compliance} and the legend in {@link #printConsistency}.</li>
 * </ul>
 *
 * <p><b>Running.</b> This is a {@code main()} tool, not a unit test, because it makes
 * real, billable LLM calls. The services read endpoints, deployment names and search
 * tuning from environment variables via {@code System.getenv} and authenticate with
 * {@code DefaultAzureCredential} (so {@code az login} is required). The companion
 * {@code run-harness.sh} (module root) exports a local {@code .env} file into the
 * environment and launches this class via {@code exec:java}.
 */
public final class TestHarness {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestHarness.class);

    private TestHarness() {
    }

    /**
     * The document(s) the harness queries target — retrieval is filtered per document id.
     * Comma-separated; override with HARNESS_DOCUMENT_IDS. Every query in user-queries.json is
     * run against EVERY id in this list, so the run matrix expands to
     * prompts × LLMs × (queries × documentIds). With more than one id, each query label is
     * suffixed with the id's first 8 characters so report rows stay distinguishable.
     */
    private static final String DEFAULT_DOCUMENT_IDS =
            "4fa52386-d5f2-4b61-bc8c-c28cb02093ee,22e543b6-9f10-49db-9119-94a41fc02002";
    private static final List<String> DOCUMENT_IDS = splitCsv(env("HARNESS_DOCUMENT_IDS", DEFAULT_DOCUMENT_IDS));

    /** Metadata field the AI Search index filters documents on. */
    private static final String DOCUMENT_ID_FILTER_KEY = "document_id";

    /**
     * How many times to repeat each (prompt × LLM × query) cell. Even at
     * temperature=0 there is non-determinism (floating-point reduction order, search
     * ranking variance), so multiple runs are needed to judge consistency. 1 for a
     * fast smoke test, 3–5 for a meaningful sample. Override with HARNESS_REPETITIONS.
     */
    private static final int REPETITIONS = intEnv("HARNESS_REPETITIONS", 3);

    /**
     * Delay in seconds inserted before each LLM call, to keep large-context requests from
     * tripping the deployment's tokens-per-minute (TPM) quota — which can surface as empty
     * {@code finish_reason=length} responses. Override with HARNESS_CALL_DELAY_SECONDS.
     */
    private static final int CALL_DELAY_SECONDS = intEnv("HARNESS_CALL_DELAY_SECONDS", 15);

    /** Prompt files under src/main/resources/prompts, in display order. The quality-comparison
     *  stage compares each prompt against its predecessor in this list. */
    private static final List<String> PROMPT_FILES = List.of(
            "v2-baseline-with-improvements",
            "v3-strict-citation-grouping",
            "v4-strict-citation-grouping-compact");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record SystemPromptConfig(String label, String prompt) {
    }

    record LlmConfig(String label, String deployment) {
    }

    record UserQueryConfig(String label, String userQuery, String userQueryPrompt, String documentId) {
    }

    record RunResult(String promptLabel, String llmLabel, String queryLabel, int iteration,
                     LlmResponse response, long durationMs, String error) {
    }

    /**
     * Citation-format compliance metrics derived from a single LLM response.
     *
     * <p>{@code proseChars}/{@code proseWords} measure the narrative answer ONLY —
     * the raw response with the {@code <FACT_MAP_JSON>} block and every inline
     * bracket marker removed — so verbosity can be compared across models
     * independently of how many citations each emitted.
     */
    record Compliance(int rawInlineMarkers, Set<Integer> inlineIds, int rawDriftMarkers,
                      int jsonEntries, Set<Integer> jsonIds,
                      boolean inlineSubsetOfJson, boolean processorSubstituted,
                      boolean jsonBlockPresent, int proseChars, int proseWords, int citedPages,
                      int sameDocStackedRuns, int renderedCitations, int strippedMarkers,
                      boolean uncitedSubstantive) {
    }

    /** Words of prose at/above which an answer counts as substantive (refusals are far shorter). */
    private static final int SUBSTANTIVE_PROSE_WORDS = 50;

    private static final CitationProcessor OUTCOME_PROCESSOR = new CitationProcessor();

    public static void main(final String[] args) throws InterruptedException {
        final List<SystemPromptConfig> systemPrompts = loadPrompts();
        final List<LlmConfig> llms = loadLlms();
        final List<UserQueryConfig> queries = loadUserQueriesFromJson();

        final EmbeddingService embeddingService = new EmbeddingService(
                requireEnv("AZURE_EMBEDDING_SERVICE_ENDPOINT"), requireEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME"));
        final AzureAISearchService searchService = new AzureAISearchService(
                requireEnv("AZURE_SEARCH_SERVICE_ENDPOINT"), requireEnv("AZURE_SEARCH_SERVICE_INDEX_NAME"));

        final Map<String, List<ChunkedEntry>> chunksByQueryLabel = retrieveChunks(queries, embeddingService, searchService);
        final List<RunResult> results = runMatrix(systemPrompts, llms, queries, chunksByQueryLabel);

        printSummary(results);
        printConsistency(results);
        printDetail(results, systemPrompts, queries);

        try {
            ResponseQualityComparator.run(results, systemPrompts, queries, embeddingService,
                    requireEnv("AZURE_OPENAI_ENDPOINT"));
        } catch (final Exception e) {
            LOGGER.warn("[quality] comparison stage failed; generation reports above are unaffected.", e);
        }
    }

    /**
     * Retrieve chunks once per query — chunks don't depend on prompt or LLM, so re-fetching
     * for every (prompt, LLM) would waste embedding + search calls.
     */
    private static Map<String, List<ChunkedEntry>> retrieveChunks(final List<UserQueryConfig> queries,
                                                                  final EmbeddingService embeddingService,
                                                                  final AzureAISearchService searchService) {
        final Map<String, List<ChunkedEntry>> chunksByQueryLabel = new LinkedHashMap<>();
        for (final UserQueryConfig uqc : queries) {
            try {
                final long t0 = System.currentTimeMillis();
                final List<ChunkedEntry> chunks = loadChunks(embeddingService, searchService, uqc.userQuery(), uqc.documentId());
                LOGGER.info("[chunks] query={} documentId={} -> {} chunks in {} ms",
                        uqc.label(), uqc.documentId(), chunks.size(), System.currentTimeMillis() - t0);
                chunksByQueryLabel.put(uqc.label(), chunks);
            } catch (final RuntimeException e) {
                LOGGER.warn("[chunks] FAIL query={}", uqc.label(), e);
                chunksByQueryLabel.put(uqc.label(), List.of());
            }
        }
        return chunksByQueryLabel;
    }

    /** Runs every (iteration × prompt × LLM × query) cell and collects the results. */
    private static List<RunResult> runMatrix(final List<SystemPromptConfig> systemPrompts,
                                             final List<LlmConfig> llms,
                                             final List<UserQueryConfig> queries,
                                             final Map<String, List<ChunkedEntry>> chunksByQueryLabel)
            throws InterruptedException {
        final List<RunResult> results = new ArrayList<>();
        for (int iter = 1; iter <= REPETITIONS; iter++) {
            Thread.sleep(Duration.ofSeconds(5).toMillis());
            LOGGER.info("========= ITERATION {} / {} =========", iter, REPETITIONS);
            for (final SystemPromptConfig spc : systemPrompts) {
                for (final LlmConfig lc : llms) {
                    final ResponseGenerationService svc = buildService(spc, lc);
                    for (final UserQueryConfig uqc : queries) {
                        results.add(runCell(svc, spc, lc, uqc, iter, chunksByQueryLabel.get(uqc.label())));
                    }
                }
            }
        }
        return results;
    }

    /** Runs one (prompt × LLM × query) cell, recording an ERROR/SKIPPED result rather than aborting. */
    private static RunResult runCell(final ResponseGenerationService svc, final SystemPromptConfig spc,
                                     final LlmConfig lc, final UserQueryConfig uqc, final int iter,
                                     final List<ChunkedEntry> chunks) throws InterruptedException {
        if (chunks == null || chunks.isEmpty()) {
            LOGGER.info("[skip] iter={} prompt={} llm={} query={} — no chunks",
                    iter, spc.label(), lc.label(), uqc.label());
            return new RunResult(spc.label(), lc.label(), uqc.label(), iter, null, 0L, "SKIPPED: no chunks for query");
        }
        if (CALL_DELAY_SECONDS > 0) {
            Thread.sleep(Duration.ofSeconds(CALL_DELAY_SECONDS).toMillis());
        }
        LOGGER.info("[run] iter={} prompt={} llm={} query={}", iter, spc.label(), lc.label(), uqc.label());
        final long t0 = System.currentTimeMillis();
        try {
            final LlmResponse r = svc.generateResponse(uqc.userQuery(), chunks, uqc.userQueryPrompt());
            return new RunResult(spc.label(), lc.label(), uqc.label(), iter, r, System.currentTimeMillis() - t0, null);
        } catch (final Exception e) {
            // Includes ChatServiceException and transient transport failures (e.g. read timeouts on
            // long gpt-5.1 reasoning calls). Record the cell as an ERROR and carry on.
            LOGGER.warn("[run] FAILED iter={} prompt={} llm={} query={}", iter, spc.label(), lc.label(), uqc.label(), e);
            return new RunResult(spc.label(), lc.label(), uqc.label(), iter, null, System.currentTimeMillis() - t0, e.toString());
        }
    }

    private static ResponseGenerationService buildService(final SystemPromptConfig spc, final LlmConfig lc) {
        final String chatEndpoint = requireEnv("AZURE_OPENAI_ENDPOINT");
        // Production path: the factory honours LLM_CHAT_SERVICE_PROVIDER and the chat
        // service applies the real isReasoningModel branch (gpt-5.1 → no temperature/top_p).
        final ChatService chat = ChatServiceFactory.getInstance(chatEndpoint, lc.deployment());
        return new ResponseGenerationService(
                chat,
                new CitationProcessor(),
                new ChunkFormatterUtility(),
                new UserInstructionService(),
                spc.prompt());
    }

    private static List<ChunkedEntry> loadChunks(final EmbeddingService embeddingService,
                                                 final AzureAISearchService searchService,
                                                 final String userQuery, final String documentId) {
        try {
            final List<Float> vectorisedUserQuery = embeddingService.embedData(userQuery);
            return searchService.search(userQuery, vectorisedUserQuery,
                    List.of(new KeyValuePair(DOCUMENT_ID_FILTER_KEY, documentId)));
        } catch (final EmbeddingServiceException | SearchServiceException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- config loading -----------------------------------------------------

    private static List<SystemPromptConfig> loadPrompts() {
        final List<SystemPromptConfig> out = new ArrayList<>();
        for (final String name : PROMPT_FILES) {
            out.add(new SystemPromptConfig(name, readPromptResource("/prompts/" + name + ".txt")));
        }
        LOGGER.info("[init] loaded {} prompts: {}", out.size(), PROMPT_FILES);
        return out;
    }

    private static String readPromptResource(final String resourcePath) {
        try (InputStream in = TestHarness.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Prompt resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to read prompt resource " + resourcePath, e);
        }
    }

    private static List<LlmConfig> loadLlms() {
        // Comma-separated deployment names; both share AZURE_OPENAI_ENDPOINT.
        final String spec = env("HARNESS_LLM_DEPLOYMENTS", "gpt-4o-response-generation,gpt-5.1");
        final List<LlmConfig> out = new ArrayList<>();
        for (final String d : spec.split(",")) {
            final String deployment = d.trim();
            if (!deployment.isEmpty()) {
                out.add(new LlmConfig(deployment, deployment));
            }
        }
        LOGGER.info("[init] LLM deployments: {}", out.stream().map(LlmConfig::deployment).toList());
        return out;
    }

    /**
     * Load queries from src/main/resources/user-queries.json (relative to module root
     * or repo root), falling back to the classpath. JSON shape:
     * <pre>{ "queries": [ { "label": "...", "userQuery": "...", "queryPrompt": "..." }, ... ] }</pre>
     * Each query is expanded against every id in {@link #DOCUMENT_IDS} (HARNESS_DOCUMENT_IDS),
     * so one query file drives the same evaluation across any number of target documents.
     * HARNESS_MAX_QUERIES caps the base queries BEFORE the document expansion.
     */
    private static List<UserQueryConfig> loadUserQueriesFromJson() {
        final Path[] candidates = {
                Paths.get("ai-document-system-prompt-harness-eval/src/main/resources/user-queries.json"),
                Paths.get("src/main/resources/user-queries.json")
        };
        try {
            JsonNode root = null;
            Path used = null;
            for (final Path p : candidates) {
                if (Files.exists(p)) {
                    root = MAPPER.readTree(p.toFile());
                    used = p;
                    break;
                }
            }
            if (root == null) {
                try (InputStream in = TestHarness.class.getResourceAsStream("/user-queries.json")) {
                    if (in == null) {
                        throw new RuntimeException("user-queries.json not found on filesystem or classpath");
                    }
                    root = MAPPER.readTree(in);
                    used = Paths.get("classpath:/user-queries.json");
                }
            }
            final List<JsonNode> queryNodes = new ArrayList<>();
            root.get("queries").forEach(queryNodes::add);
            final int maxQueries = intEnv("HARNESS_MAX_QUERIES", 0);
            final List<JsonNode> selected = (maxQueries > 0 && queryNodes.size() > maxQueries)
                    ? queryNodes.subList(0, maxQueries) : queryNodes;

            final List<UserQueryConfig> out = new ArrayList<>();
            for (final JsonNode q : selected) {
                for (final String documentId : DOCUMENT_IDS) {
                    out.add(new UserQueryConfig(
                            queryLabelFor(q.get("label").asText(), documentId),
                            q.get("userQuery").asText(),
                            q.get("queryPrompt").asText(),
                            documentId));
                }
            }
            LOGGER.info("[init] loaded {} user queries from {} ({} selected x {} documentIds = {} rows); documentIds={}",
                    queryNodes.size(), used, selected.size(), DOCUMENT_IDS.size(), out.size(), DOCUMENT_IDS);
            return out;
        } catch (final Exception e) {
            throw new RuntimeException("Failed to parse user-queries.json", e);
        }
    }

    // ---- reporting ----------------------------------------------------------

    private static void printSummary(final List<RunResult> results) {
        LOGGER.info("");
        LOGGER.info("================================= SUMMARY (per-run) =================================");
        LOGGER.info(String.format("%4s | %-26s | %-26s | %-22s | %6s | %6s | %6s | %6s | %6s",
                "iter", "query", "prompt", "llm", "status", "ms", "chars", "prose", "words"));
        LOGGER.info("-".repeat(160));
        for (final RunResult r : results) {
            final Compliance c = r.response() != null ? computeCompliance(r.response()) : null;
            LOGGER.info(String.format("%4d | %-26s | %-26s | %-22s | %6s | %6d | %6d | %6d | %6d",
                    r.iteration(),
                    truncate(r.queryLabel(), 26),
                    truncate(r.promptLabel(), 26),
                    truncate(r.llmLabel(), 22),
                    truncate(statusOf(r), 6),
                    r.durationMs(),
                    rawLen(r),
                    c != null ? c.proseChars() : 0,
                    c != null ? c.proseWords() : 0));
        }
    }

    private static String statusOf(final RunResult r) {
        if (r.error() != null) {
            return r.error().startsWith("SKIPPED") ? "SKIPPED" : "ERROR";
        }
        return r.response() != null ? String.valueOf(r.response().status()) : "n/a";
    }

    private static int rawLen(final RunResult r) {
        return (r.response() != null && r.response().rawLlmResponse() != null)
                ? r.response().rawLlmResponse().length() : 0;
    }

    /** Per-(query, prompt, LLM) cell aggregate across the {@link #REPETITIONS} iterations. */
    private record CellStats(int ok, int jsonPresent, int matched, int substituted,
                             long proseAvg, long wordAvg, long citeAvg, long pageAvg, long stackAvg,
                             int uncited) {
    }

    /** Aggregate stats per (query, prompt, LLM) cell across the {@link #REPETITIONS} iterations. */
    private static void printConsistency(final List<RunResult> results) {
        final Map<String, List<RunResult>> grouped = new LinkedHashMap<>();
        for (final RunResult r : results) {
            grouped.computeIfAbsent(r.queryLabel() + "|" + r.promptLabel() + "|" + r.llmLabel(),
                    k -> new ArrayList<>()).add(r);
        }

        LOGGER.info("");
        LOGGER.info("======== CONSISTENCY ACROSS {} ITERATIONS ========", REPETITIONS);
        LOGGER.info(String.format("%-26s | %-22s | %-22s | %-7s | %-7s | %-7s | %-7s | %8s | %7s | %5s | %5s | %6s | %7s",
                "query", "prompt", "llm", "ok", "json", "match", "subst", "proseAvg", "wordAvg", "cites", "pages", "stacks", "uncited"));
        LOGGER.info("-".repeat(179));

        for (final List<RunResult> runs : grouped.values()) {
            final RunResult first = runs.get(0);
            final CellStats s = computeCellStats(runs);
            final int n = runs.size();
            LOGGER.info(String.format("%-26s | %-22s | %-22s | %5d/%d | %5d/%d | %5d/%d | %5d/%d | %8d | %7d | %5d | %5d | %6d | %7d",
                    truncate(first.queryLabel(), 26), truncate(first.promptLabel(), 22), truncate(first.llmLabel(), 22),
                    s.ok(), n, s.jsonPresent(), n, s.matched(), n, s.substituted(), n,
                    s.proseAvg(), s.wordAvg(), s.citeAvg(), s.pageAvg(), s.stackAvg(), s.uncited()));
        }

        printConsistencyLegend();
    }

    private static CellStats computeCellStats(final List<RunResult> runs) {
        int ok = 0;
        int jsonPresent = 0;
        int matched = 0;
        int substituted = 0;
        long proseSum = 0;
        long wordSum = 0;
        long citeSum = 0;
        long pageSum = 0;
        long stackSum = 0;
        int uncited = 0;
        for (final RunResult r : runs) {
            if (r.error() != null || r.response() == null
                    || !"ANSWER_GENERATED".equals(String.valueOf(r.response().status()))) {
                continue;
            }
            ok++;
            final Compliance c = computeCompliance(r.response());
            jsonPresent += c.jsonBlockPresent() ? 1 : 0;
            matched += c.inlineSubsetOfJson() ? 1 : 0;
            substituted += c.processorSubstituted() ? 1 : 0;
            proseSum += c.proseChars();
            wordSum += c.proseWords();
            citeSum += c.jsonIds().size();
            pageSum += c.citedPages();
            stackSum += c.sameDocStackedRuns();
            uncited += c.uncitedSubstantive() ? 1 : 0;
        }
        final int denom = ok > 0 ? ok : 1;
        return new CellStats(ok, jsonPresent, matched, substituted,
                proseSum / denom, wordSum / denom, citeSum / denom, pageSum / denom, stackSum / denom,
                uncited);
    }

    private static void printConsistencyLegend() {
        LOGGER.info("");
        LOGGER.info("Legend:");
        LOGGER.info("  ok       = runs that returned ANSWER_GENERATED");
        LOGGER.info("  json     = runs whose raw output contained a parseable <FACT_MAP_JSON> block");
        LOGGER.info("             (json < ok on GPT-5.1 ⇒ reasoning-token truncation; raise LLM_MODEL_RESPONSE_MAX_TOKENS)");
        LOGGER.info("  match    = runs where every inline [N] has a matching JSON entry (renderable)");
        LOGGER.info("  subst    = runs where CitationProcessor actually substituted at least one ::(Source …)");
        LOGGER.info("  proseAvg = mean answer length in characters, EXCLUDING the FACT_MAP_JSON block and");
        LOGGER.info("             all inline [N] markers — a citation-independent measure of verbosity");
        LOGGER.info("  wordAvg  = mean answer length in words, same exclusions (lower = more concise)");
        LOGGER.info("  cites    = mean distinct citations (FACT_MAP_JSON entries) — coverage guard");
        LOGGER.info("  pages    = mean total source pages cited across entries — coverage guard");
        LOGGER.info("             (when cutting wordAvg, cites/pages should hold ⇒ padding removed, not facts)");
        LOGGER.info("  stacks   = mean same-document stacked runs: adjacent [N][M] markers whose ids resolve");
        LOGGER.info("             to the SAME documentId — should be 0; the prompt requires one merged citation");
        LOGGER.info("             (adjacent markers for DIFFERENT documents are legitimate and not counted)");
        LOGGER.info("  uncited  = substantive answers (>= " + SUBSTANTIVE_PROSE_WORDS + " prose words) with ZERO rendered citations —");
        LOGGER.info("             the citation guard's rejection signal; legitimate short refusals do not count");
    }

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

    /**
     * Extract citation-format compliance metrics from one LLM response. Compares:
     * inline {@code [N]} markers (bare) vs any-bracket-citation matches (difference = drift),
     * JSON citationId values, whether every inline id appears in the JSON, whether
     * {@link CitationProcessor} actually substituted, and whether a parseable JSON block exists.
     */
    private static Compliance computeCompliance(final LlmResponse response) {
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

    /** Applies the same pre-call delay as generation calls; restores the interrupt flag if interrupted. */
    static void pause() {
        if (CALL_DELAY_SECONDS <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofSeconds(CALL_DELAY_SECONDS).toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printDetail(final List<RunResult> results,
                                    final List<SystemPromptConfig> systemPrompts,
                                    final List<UserQueryConfig> queries) {
        final List<RunResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator
                .comparing(RunResult::queryLabel)
                .thenComparing(RunResult::promptLabel)
                .thenComparing(RunResult::llmLabel)
                .thenComparingInt(RunResult::iteration));
        for (final UserQueryConfig uqc : queries) {
            LOGGER.info("");
            LOGGER.info("#".repeat(80));
            LOGGER.info("# QUERY: {} — {}", uqc.label(), uqc.userQuery());
            LOGGER.info("#".repeat(80));
            for (final SystemPromptConfig spc : systemPrompts) {
                LOGGER.info("");
                LOGGER.info("=== prompt: {} ===", spc.label());
                for (final RunResult r : sorted) {
                    if (r.queryLabel().equals(uqc.label()) && r.promptLabel().equals(spc.label())) {
                        printRunDetail(r);
                    }
                }
            }
        }
    }

    private static void printRunDetail(final RunResult r) {
        LOGGER.info("");
        LOGGER.info("--- llm: {} | iter: {} | {} ms ---", r.llmLabel(), r.iteration(), r.durationMs());
        if (r.error() != null) {
            LOGGER.info("ERROR: {}", r.error());
            return;
        }
        if (r.response() == null) {
            return;
        }
        final Compliance c = computeCompliance(r.response());
        LOGGER.info("status: {} | jsonBlock: {} | inlineIds: {} | jsonIds: {}"
                        + " | drift: {} | match: {} | subst: {} | proseChars: {} | proseWords: {}"
                        + " | citedPages: {} | sameDocStacks: {} | rendered: {} | stripped: {} | uncitedSubstantive: {}",
                r.response().status(), c.jsonBlockPresent(), c.inlineIds(), c.jsonIds(),
                c.rawDriftMarkers(), c.inlineSubsetOfJson(), c.processorSubstituted(),
                c.proseChars(), c.proseWords(), c.citedPages(), c.sameDocStackedRuns(),
                c.renderedCitations(), c.strippedMarkers(), c.uncitedSubstantive());
        LOGGER.info("");
        LOGGER.info("RAW RESPONSE:");
        LOGGER.info(safe(r.response().rawLlmResponse()));
        LOGGER.info("");
        LOGGER.info("FORMATTED RESPONSE (after CitationProcessor):");
        LOGGER.info(safe(r.response().formattedLlmResponse()));
    }

    // ---- env + string helpers ----------------------------------------------

    /** Splits a comma-separated value into trimmed, non-empty tokens. */
    private static List<String> splitCsv(final String csv) {
        final List<String> out = new ArrayList<>();
        for (final String token : csv.split(",")) {
            if (!token.trim().isEmpty()) {
                out.add(token.trim());
            }
        }
        return out;
    }

    /**
     * Report label for a (query, documentId) row. With a single target document the plain query
     * label is kept; with several, the id's first 8 chars are appended so rows stay distinct.
     */
    private static String queryLabelFor(final String label, final String documentId) {
        if (DOCUMENT_IDS.size() < 2) {
            return label;
        }
        final String shortId = documentId.length() > 8 ? documentId.substring(0, 8) : documentId;
        return label + " @" + shortId;
    }

    static String env(final String key, final String dflt) {
        final String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static String requireEnv(final String key) {
        final String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("Required environment variable not set: " + key
                    + " (run via run-harness.sh, which exports the module's .env file)");
        }
        return v;
    }

    static int intEnv(final String key, final int dflt) {
        final String v = System.getenv(key);
        try {
            return (v == null || v.isBlank()) ? dflt : Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }

    private static String safe(final String s) {
        return s == null ? "(null)" : s;
    }

    private static String truncate(final String s, final int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
