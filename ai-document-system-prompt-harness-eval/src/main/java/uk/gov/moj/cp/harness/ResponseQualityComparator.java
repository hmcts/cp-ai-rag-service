package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.ai.util.VectorSimilarityUtil.cosineSimilarity;

import uk.gov.moj.cp.ai.client.ChatServiceFactory;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares response QUALITY across run variants. Four comparison dimensions are supported:
 *
 * <ul>
 *   <li><b>System prompts</b> — with two or more entries in PROMPT_FILES, each prompt is
 *       compared against its predecessor (chain-wise), rows paired per (query, llm, iteration).</li>
 *   <li><b>Query versions</b> — with two or more versions in the query-set file (same queries
 *       matched by queryId, different queryPrompt wording), each version is compared against its
 *       predecessor, rows paired per (base query, prompt, llm, iteration). This measures what a
 *       query-prompt revision changes under an identical system prompt.</li>
 *   <li><b>Models (LLMs)</b> — with two or more entries in HARNESS_LLM_DEPLOYMENTS, each model is
 *       compared against its predecessor, rows paired per (query, prompt, iteration): identical
 *       system prompt, query instruction and retrieved chunks, so the only variable is the model.
 *       Each side's mean/min/max generation latency is reported alongside the quality verdicts.</li>
 *   <li><b>Cross-cut (version × model)</b> — the single axes above each vary one dimension; this
 *       varies two together. When both a query-version axis and a model axis are present, one
 *       diagonal is compared: first-declared version on the first-declared model vs last-declared
 *       version on the last-declared model. With the usual ordering (versions {@code [prod, test]},
 *       models {@code [<old>, <new>]}) that is <em>prod on the old model vs test on the new model</em>
 *       — the production-vs-migration-target comparison. Rows paired per (base query, prompt,
 *       iteration); both the query instruction and the model differ across the pair. Override the
 *       two corners with {@code HARNESS_CROSSCUT="versionA:modelA vs versionB:modelB"}.</li>
 * </ul>
 *
 * <p>Three layers per paired row:
 * <ol>
 *   <li><b>Semantic similarity</b> — cosine between embeddings of the citation-stripped prose of
 *       the two answers (reuses the production {@link EmbeddingService}). High cosine on
 *       text-embedding models saturates (&gt;0.9 is typical for same-topic text), so read it as a
 *       red-flag detector: a pair well below the batch mean has diverged.</li>
 *   <li><b>Structure counts</b> — deterministic per answer: h1 violations / h2+ headings / bullet
 *       lines, so format drift is visible without a model in the loop.</li>
 *   <li><b>LLM judge</b> — HARNESS_JUDGE_DEPLOYMENT (default gpt-5.1) scores factual parity
 *       (EQUIVALENT | A_RICHER | B_RICHER | DIVERGENT, with the material facts missing from each
 *       side) and 1–5 adherence of each answer to <em>its own</em> query instruction — the two
 *       sides may carry different instructions when comparing query versions. Disable with
 *       HARNESS_JUDGE=false.</li>
 * </ol>
 *
 * <p>Every failure (embedding, judge call, judge-JSON parse) downgrades to {@code n/a} for that
 * row — the comparison stage never fails the harness run.
 */
final class ResponseQualityComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseQualityComparator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String JUDGE_SYSTEM_PROMPT = """
            You are a strict evaluation judge. You compare two answers (A and B) produced for the \
            same underlying legal query over the same source documents. The two answers may have \
            been generated under different system prompts or different query instructions — each \
            answer's instruction is supplied. Judge only the two answers given; do not reward \
            verbosity. Ignore citation artefacts such as "::(Source: ...)" or bracketed numbers.
            Return ONLY a minified JSON object with exactly these keys:
            {"semanticVerdict":"EQUIVALENT|A_RICHER|B_RICHER|DIVERGENT",\
            "factsMissingFromA":["..."],"factsMissingFromB":["..."],\
            "structureScoreA":1,"structureScoreB":1,"note":"..."}
            semanticVerdict: EQUIVALENT = both contain the same material facts; A_RICHER / B_RICHER \
            = that answer contains material facts the other lacks; DIVERGENT = each misses material \
            facts the other has. factsMissingFromA/B: material facts absent from that answer but \
            present in the other — short phrases, at most 5 each, [] if none. An answer is NOT \
            missing facts that its own instruction expressly excludes or caps. structureScoreA/B: \
            integer 1-5 scoring how well that answer follows the structure, sections, ordering, \
            formatting and any length limits requested in ITS OWN instruction (5 = fully adherent). \
            note: one short sentence on the most important difference.""";

    private ResponseQualityComparator() {
    }

    /** One judged row; {@code verdict} is n/a when the judge is disabled or failed. */
    record JudgeResult(String verdict, int missingFromA, int missingFromB,
                               int structureA, int structureB, String note) {
    }

    record StructureCounts(int h1, int headings, int bullets) {
        @Override
        public String toString() {
            return h1 + "/" + headings + "/" + bullets;
        }
    }

    static void run(final List<TestHarness.RunResult> results,
                    final List<TestHarness.SystemPromptConfig> prompts,
                    final List<TestHarness.UserQueryConfig> queries,
                    final EmbeddingService embeddingService,
                    final String chatEndpoint) {
        final List<String> versions = queries.stream()
                .map(TestHarness.UserQueryConfig::version)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        final List<String> llms = results.stream()
                .map(TestHarness.RunResult::llmLabel)
                .distinct()
                .toList();
        if (prompts.size() < 2 && versions.size() < 2 && llms.size() < 2) {
            return;
        }
        final boolean judgeEnabled = !"false".equalsIgnoreCase(HarnessEnv.env("HARNESS_JUDGE", "true"));
        final String judgeDeployment = HarnessEnv.env("HARNESS_JUDGE_DEPLOYMENT", "gpt-5.1");
        final ChatService judge = judgeEnabled ? ChatServiceFactory.getInstance(chatEndpoint, judgeDeployment) : null;

        final Map<String, TestHarness.UserQueryConfig> queryByLabel = new HashMap<>();
        queries.forEach(q -> queryByLabel.put(q.label(), q));
        final Map<String, List<Float>> embeddingCache = new HashMap<>();

        LOGGER.info("");
        LOGGER.info("======== QUALITY COMPARISON (judge: {}) ========", judgeEnabled ? judgeDeployment : "disabled");

        if (prompts.size() >= 2) {
            final Map<String, Map<String, TestHarness.RunResult>> rows = groupRows(results,
                    r -> r.queryLabel() + "|" + r.llmLabel() + "|" + r.iteration(),
                    TestHarness.RunResult::promptLabel);
            for (int i = 1; i < prompts.size(); i++) {
                comparePair("system prompt", prompts.get(i - 1).label(), prompts.get(i).label(),
                        rows, queryByLabel, embeddingCache, embeddingService, judge);
            }
        }
        if (versions.size() >= 2) {
            final Map<String, Map<String, TestHarness.RunResult>> rows = groupRows(results,
                    r -> baseLabel(r, queryByLabel) + "|" + r.promptLabel() + "|" + r.llmLabel() + "|" + r.iteration(),
                    r -> versionOf(r, queryByLabel));
            for (int i = 1; i < versions.size(); i++) {
                comparePair("query version", versions.get(i - 1), versions.get(i),
                        rows, queryByLabel, embeddingCache, embeddingService, judge);
            }
        }
        if (llms.size() >= 2) {
            // Same prompt, same query instruction, same retrieved chunks — the model is the only
            // variable, so verdicts and latency deltas here are directly attributable to the model.
            final Map<String, Map<String, TestHarness.RunResult>> rows = groupRows(results,
                    r -> r.queryLabel() + "|" + r.promptLabel() + "|" + r.iteration(),
                    TestHarness.RunResult::llmLabel);
            for (int i = 1; i < llms.size(); i++) {
                comparePair("model", llms.get(i - 1), llms.get(i),
                        rows, queryByLabel, embeddingCache, embeddingService, judge);
            }
        }
        if (versions.size() >= 2 && llms.size() >= 2) {
            // Cross-cut diagonal: vary version AND model together. The variant key combines both,
            // so a single row (fixed base query, prompt, document, iteration) holds up to
            // versions×llms answers; comparePair picks out the two named corners. Both the query
            // instruction and the model differ across the pair — the judge already handles the
            // former (each side is judged against its own instruction) and is model-agnostic.
            final Map<String, Map<String, TestHarness.RunResult>> rows = groupRows(results,
                    r -> baseLabel(r, queryByLabel) + "|" + r.promptLabel() + "|" + r.iteration(),
                    r -> versionOf(r, queryByLabel) + "|" + r.llmLabel());
            final String[] corners = crossCutCorners(versions, llms);
            comparePair("cross-cut (version×model)", corners[0], corners[1],
                    rows, queryByLabel, embeddingCache, embeddingService, judge);
        }
        printLegend();
    }

    /** Rows keyed by {@code rowKey}, each holding {@code variantKey} → result. */
    static Map<String, Map<String, TestHarness.RunResult>> groupRows(
            final List<TestHarness.RunResult> results,
            final Function<TestHarness.RunResult, String> rowKey,
            final Function<TestHarness.RunResult, String> variantKey) {
        final Map<String, Map<String, TestHarness.RunResult>> rows = new LinkedHashMap<>();
        for (final TestHarness.RunResult r : results) {
            rows.computeIfAbsent(rowKey.apply(r), k -> new LinkedHashMap<>()).put(variantKey.apply(r), r);
        }
        return rows;
    }

    /** The row's query label with its {@code #version} suffix removed (pairing key across versions). */
    static String baseLabel(final TestHarness.RunResult r,
                            final Map<String, TestHarness.UserQueryConfig> queryByLabel) {
        final TestHarness.UserQueryConfig cfg = queryByLabel.get(r.queryLabel());
        if (cfg == null || cfg.version() == null) {
            return r.queryLabel();
        }
        return r.queryLabel().replace(" #" + cfg.version(), "");
    }

    static String versionOf(final TestHarness.RunResult r,
                            final Map<String, TestHarness.UserQueryConfig> queryByLabel) {
        final TestHarness.UserQueryConfig cfg = queryByLabel.get(r.queryLabel());
        return cfg == null || cfg.version() == null ? "?" : cfg.version();
    }

    /**
     * The two {@code version|llm} corners for the cross-cut comparison. Default is the diagonal
     * {@code first-version|first-model} vs {@code last-version|last-model} (declared order — see
     * the class Javadoc). {@code HARNESS_CROSSCUT="versionA:modelA vs versionB:modelB"} overrides
     * both corners explicitly.
     */
    private static String[] crossCutCorners(final List<String> versions, final List<String> llms) {
        return crossCutCorners(versions, llms, HarnessEnv.env("HARNESS_CROSSCUT", ""));
    }

    /**
     * Pure core of {@link #crossCutCorners(List, List)}: {@code spec} is the raw HARNESS_CROSSCUT
     * value (blank ⇒ the default diagonal). Package-private so the corner-selection logic is unit
     * testable without touching the environment.
     */
    static String[] crossCutCorners(final List<String> versions, final List<String> llms, final String spec) {
        if (!spec.isBlank()) {
            // Bounded quantifiers ({1,16}) so the split cannot backtrack super-linearly — this
            // satisfies SonarQube's S5852 (regex-DoS) check, which a possessive quantifier did not.
            final String[] sides = spec.split("\\s{1,16}vs\\s{1,16}", 2);
            if (sides.length == 2) {
                return new String[] {cornerKey(sides[0]), cornerKey(sides[1])};
            }
            LOGGER.warn("[quality] HARNESS_CROSSCUT '{}' is not of the form 'A vs B' — using the default diagonal", spec);
        }
        return new String[] {
                versions.get(0) + "|" + llms.get(0),
                versions.get(versions.size() - 1) + "|" + llms.get(llms.size() - 1),
        };
    }

    /** {@code "prod:gpt-4o"} → {@code "prod|gpt-4o"} (the variant-key form; llm labels carry no colon). */
    static String cornerKey(final String corner) {
        final String c = corner.trim();
        final int i = c.indexOf(':');
        return i < 0 ? c : c.substring(0, i).trim() + "|" + c.substring(i + 1).trim();
    }

    private static void comparePair(final String dimension, final String keyA, final String keyB,
                                    final Map<String, Map<String, TestHarness.RunResult>> rows,
                                    final Map<String, TestHarness.UserQueryConfig> queryByLabel,
                                    final Map<String, List<Float>> embeddingCache,
                                    final EmbeddingService embeddingService,
                                    final ChatService judge) {
        LOGGER.info("");
        LOGGER.info("---- {} A: {}   vs   B: {} ----", dimension, keyA, keyB);
        double cosSum = 0;
        int cosN = 0;
        final Map<String, Integer> verdicts = new TreeMap<>();
        int structASum = 0;
        int structBSum = 0;
        int judged = 0;
        final LatencyStats latencyA = new LatencyStats();
        final LatencyStats latencyB = new LatencyStats();

        for (final Map.Entry<String, Map<String, TestHarness.RunResult>> row : rows.entrySet()) {
            final TestHarness.RunResult a = okResult(row.getValue().get(keyA));
            final TestHarness.RunResult b = okResult(row.getValue().get(keyB));
            if (a == null || b == null) {
                continue;
            }
            latencyA.add(a.durationMs());
            latencyB.add(b.durationMs());
            final Double cos = cosineOf(row.getKey(), keyA, a, keyB, b, embeddingCache, embeddingService);
            if (cos != null) {
                cosSum += cos;
                cosN++;
            }
            final JudgeResult j = judgeRow(judge, queryByLabel.get(a.queryLabel()), queryByLabel.get(b.queryLabel()), a, b);
            if (j != null) {
                verdicts.merge(j.verdict(), 1, Integer::sum);
                structASum += j.structureA();
                structBSum += j.structureB();
                judged++;
            }
            logRow(row.getKey(), cos, j, a, b);
        }

        LOGGER.info("  == pair aggregate: rows compared {} | mean cos {} | verdicts {} | mean structA {} structB {}",
                cosN, cosN == 0 ? "n/a" : String.format("%.4f", cosSum / cosN), verdicts,
                judged == 0 ? "n/a" : String.format("%.1f", (double) structASum / judged),
                judged == 0 ? "n/a" : String.format("%.1f", (double) structBSum / judged));
        LOGGER.info("  == generation latency: A {} | B {}", latencyA, latencyB);
    }

    /** Mean/min/max generation duration (ms) across the paired rows of one comparison side. */
    private static final class LatencyStats {
        private long sum;
        private long min = Long.MAX_VALUE;
        private long max;
        private int n;

        void add(final long ms) {
            sum += ms;
            min = Math.min(min, ms);
            max = Math.max(max, ms);
            n++;
        }

        @Override
        public String toString() {
            return n == 0 ? "n/a" : String.format("mean %.1fs min %.1fs max %.1fs (n=%d)",
                    sum / 1000.0 / n, min / 1000.0, max / 1000.0, n);
        }
    }

    private static void logRow(final String rowKey, final Double cos, final JudgeResult j,
                               final TestHarness.RunResult a, final TestHarness.RunResult b) {
        final StructureCounts sa = structureOf(a.response().formattedLlmResponse());
        final StructureCounts sb = structureOf(b.response().formattedLlmResponse());
        LOGGER.info(String.format("%-40s | cos %-6s | %-10s | missA %s missB %s | judgeStruct A%s B%s | mdStruct A %-8s B %-8s | ms A%-6d B%-6d | %s",
                truncate(rowKey, 40),
                cos == null ? "n/a" : String.format("%.4f", cos),
                j == null ? "n/a" : j.verdict(),
                j == null ? "-" : j.missingFromA(), j == null ? "-" : j.missingFromB(),
                j == null ? "-" : j.structureA(), j == null ? "-" : j.structureB(),
                sa, sb,
                a.durationMs(), b.durationMs(),
                j == null ? "" : j.note()));
    }

    /** Result only when the run generated an answer. */
    static TestHarness.RunResult okResult(final TestHarness.RunResult r) {
        if (r == null || r.error() != null || r.response() == null
                || !"ANSWER_GENERATED".equals(String.valueOf(r.response().status()))) {
            return null;
        }
        return r;
    }

    private static Double cosineOf(final String rowKey,
                                   final String labelA, final TestHarness.RunResult a,
                                   final String labelB, final TestHarness.RunResult b,
                                   final Map<String, List<Float>> cache,
                                   final EmbeddingService embeddingService) {
        try {
            final List<Float> va = embeddingOf(rowKey + "|" + labelA, a, cache, embeddingService);
            final List<Float> vb = embeddingOf(rowKey + "|" + labelB, b, cache, embeddingService);
            return (va == null || vb == null) ? null : cosineSimilarity(va, vb);
        } catch (final Exception e) {
            LOGGER.warn("[quality] embedding failed for {}: {}", rowKey, e.getMessage());
            return null;
        }
    }

    private static List<Float> embeddingOf(final String cacheKey, final TestHarness.RunResult r,
                                           final Map<String, List<Float>> cache,
                                           final EmbeddingService embeddingService) throws Exception {
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        final String prose = TestHarness.proseOf(r.response().rawLlmResponse());
        final List<Float> vector = prose.isEmpty() ? null : embeddingService.embedData(prose);
        cache.put(cacheKey, vector);
        return vector;
    }

    /** h1 violations / h2+ headings / bullet lines — deterministic markdown structure counts. */
    static StructureCounts structureOf(final String text) {
        int h1 = 0;
        int headings = 0;
        int bullets = 0;
        for (final String line : (text == null ? "" : text).split("\n")) {
            final String t = line.strip();
            if (t.startsWith("# ")) {
                h1++;
            } else if (t.startsWith("##")) {
                headings++;
            }
            if (t.startsWith("- ") || t.startsWith("* ")) {
                bullets++;
            }
        }
        return new StructureCounts(h1, headings, bullets);
    }

    private static JudgeResult judgeRow(final ChatService judge,
                                        final TestHarness.UserQueryConfig queryA,
                                        final TestHarness.UserQueryConfig queryB,
                                        final TestHarness.RunResult a, final TestHarness.RunResult b) {
        if (judge == null || queryA == null || queryB == null) {
            return null;
        }
        try {
            TestHarness.pause();
            final String content = "QUERY INSTRUCTION FOR ANSWER A (requested format):\n" + queryA.userQueryPrompt()
                    + "\n\nQUERY INSTRUCTION FOR ANSWER B (requested format):\n" + queryB.userQueryPrompt()
                    + "\n\nUSER QUERY FOR ANSWER A:\n" + queryA.userQuery()
                    + "\n\nUSER QUERY FOR ANSWER B:\n" + queryB.userQuery()
                    + "\n\nANSWER A:\n" + a.response().formattedLlmResponse()
                    + "\n\nANSWER B:\n" + b.response().formattedLlmResponse();
            return judge.callModel(JUDGE_SYSTEM_PROMPT, content, String.class)
                    .map(ResponseQualityComparator::parseJudgeJson)
                    .orElse(null);
        } catch (final Exception e) {
            LOGGER.warn("[quality] judge call failed for {}: {}", a.queryLabel(), e.getMessage());
            return null;
        }
    }

    /** Lenient extraction: first '{' to last '}' of the judge reply, then Jackson. */
    static JudgeResult parseJudgeJson(final String reply) {
        try {
            final int open = reply.indexOf('{');
            final int close = reply.lastIndexOf('}');
            if (open < 0 || close <= open) {
                return null;
            }
            final JsonNode n = MAPPER.readTree(reply.substring(open, close + 1));
            return new JudgeResult(
                    n.path("semanticVerdict").asText("UNPARSED"),
                    n.path("factsMissingFromA").size(),
                    n.path("factsMissingFromB").size(),
                    n.path("structureScoreA").asInt(0),
                    n.path("structureScoreB").asInt(0),
                    truncate(n.path("note").asText(""), 100));
        } catch (final Exception e) {
            LOGGER.warn("[quality] judge JSON unparseable: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void printLegend() {
        LOGGER.info("");
        LOGGER.info("Quality legend:");
        LOGGER.info("  cos        = cosine similarity of citation-stripped prose embeddings (A vs B). Saturates high;");
        LOGGER.info("               read relatively — a row well below the batch mean has semantically diverged");
        LOGGER.info("  verdict    = judge's factual-parity verdict; missA/missB = material facts missing from that side");
        LOGGER.info("               (facts an answer's own instruction excludes or caps are not counted as missing)");
        LOGGER.info("  judgeStruct= judge's 1-5 adherence of each answer to ITS OWN query instruction (incl. length limits)");
        LOGGER.info("  mdStruct   = deterministic counts per answer: h1-violations/h2+ headings/bullet lines");
        LOGGER.info("  ms A/B     = generation latency of each side's answer (per row); the pair aggregate adds");
        LOGGER.info("               mean/min/max per side — on the model dimension this is the model speed comparison");
    }
}
