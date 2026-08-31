package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.ai.util.VectorSimilarityUtil.cosineSimilarity;
import static uk.gov.moj.cp.harness.HarnessEnv.env;
import static uk.gov.moj.cp.harness.HarnessEnv.intEnv;
import static uk.gov.moj.cp.harness.HarnessEnv.requireEnv;

import uk.gov.moj.cp.ai.client.ChatServiceFactory;
import uk.gov.moj.cp.ai.service.ChatService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline judge pass over two PERSISTED harness runs ({@link RunResultStore} files) — quality
 * verdicts without regenerating a single answer. Pairs rows by (queryLabel, promptLabel,
 * iteration), sends each pair to a pluggable judge using the exact judge contract of the in-run
 * comparator ({@link ResponseQualityComparator#JUDGE_SYSTEM_PROMPT}), prints the same row/
 * aggregate views, and persists the verdicts (with the judge's identity) to
 * {@code harness-results/judge-<timestamp>.json}.
 *
 * <p>This exists so baselines stay durable: an expensive cloud run (e.g. the gpt-4o/gpt-5.1
 * baseline) is generated once, then any later candidate — typically a locally hosted
 * open-source model — is judged against it answer-for-answer. The judge itself is swappable,
 * so a provisional local judge (no cloud access needed) can be re-run later as gpt-5.1 with
 * one env change, over the same files.
 *
 * <p>Configuration (env; none of these live in .env, so plain shell exports work):
 * <ul>
 *   <li>{@code HARNESS_JUDGE_FILE_A} / {@code HARNESS_JUDGE_FILE_B} — the two run files.
 *       A is the reference/baseline side, B the candidate.</li>
 *   <li>{@code HARNESS_JUDGE_MODEL_A} / {@code HARNESS_JUDGE_MODEL_B} — which llmLabel's rows
 *       to use from each file; optional when the file contains a single model.</li>
 *   <li>{@code HARNESS_JUDGE_LLM} — the judge, as {@code [provider:]deployment[@endpoint]}
 *       (same syntax as HARNESS_LLM_DEPLOYMENTS), e.g.
 *       {@code local:gpt-oss-120b@http://localhost:1234/v1} or {@code gpt-5.1}.</li>
 *   <li>{@code HARNESS_JUDGE_COSINE} — {@code off} (default), or
 *       {@code local:<model>@<http-endpoint>} to compute the prose-cosine divergence flag with
 *       a local OpenAI-compatible embedding model (e.g. nomic via LM Studio). The space differs
 *       from the Azure capture embeddings, which is fine: cosine is only compared within this
 *       run's batch.</li>
 *   <li>{@code HARNESS_JUDGE_DELAY_SECONDS} — pacing before each judge call (default 0;
 *       set for cloud judges with TPM quotas).</li>
 * </ul>
 *
 * <p>Latency columns are echoed from the persisted rows for context but are NOT comparable
 * across files generated on different infrastructure (cloud vs local hardware).
 */
public final class BaselineJudgeTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineJudgeTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUERY_FILE_DIR = "user-queries";
    private static final String RESULTS_DIR = "harness-results";
    private static final String MODULE_DIR_NAME = "ai-document-system-prompt-harness-eval";

    private BaselineJudgeTool() {
    }

    /** One generated row lifted from a persisted run file. */
    record PersistedRow(String queryLabel, String promptLabel, int iteration,
                        long durationMs, String raw, String formatted) {
    }

    public static void main(final String[] args) throws Exception {
        final Path fileA = resolve(requireEnv("HARNESS_JUDGE_FILE_A"));
        final Path fileB = resolve(requireEnv("HARNESS_JUDGE_FILE_B"));
        final JsonNode runA = MAPPER.readTree(fileA.toFile());
        final JsonNode runB = MAPPER.readTree(fileB.toFile());

        final String modelA = selectModel(runA, env("HARNESS_JUDGE_MODEL_A", ""), "A");
        final String modelB = selectModel(runB, env("HARNESS_JUDGE_MODEL_B", ""), "B");
        final Map<String, PersistedRow> rowsA = rowsOf(runA, modelA);
        final Map<String, PersistedRow> rowsB = rowsOf(runB, modelB);

        final String queryFile = runA.get("meta").get("queryFile").asText();
        if (!queryFile.equals(runB.get("meta").get("queryFile").asText())) {
            LOGGER.warn("[judge] run files used different query files ('{}' vs '{}') — rows only pair where labels match",
                    queryFile, runB.get("meta").get("queryFile").asText());
        }
        final Map<String, String[]> instructionsByLabel = loadInstructions(queryFile);

        final String judgeEntry = requireEnv("HARNESS_JUDGE_LLM");
        final ChatService judge = buildJudge(judgeEntry);
        final LocalEmbedder embedder = LocalEmbedder.fromEnv(env("HARNESS_JUDGE_COSINE", "off"));
        final int delaySeconds = intEnv("HARNESS_JUDGE_DELAY_SECONDS", 0);

        LOGGER.info("[judge] A: {} ({} rows, model {})", fileA.getFileName(), rowsA.size(), modelA);
        LOGGER.info("[judge] B: {} ({} rows, model {})", fileB.getFileName(), rowsB.size(), modelB);
        LOGGER.info("[judge] judge: {} | cosine: {}", judgeEntry, embedder == null ? "off" : "local");

        double cosSum = 0;
        int cosN = 0;
        final Map<String, Integer> verdicts = new TreeMap<>();
        int structASum = 0;
        int structBSum = 0;
        int judged = 0;
        int unpaired = 0;
        final List<Map<String, Object>> outRows = new ArrayList<>();

        for (final Map.Entry<String, PersistedRow> e : rowsA.entrySet()) {
            final PersistedRow a = e.getValue();
            final PersistedRow b = rowsB.get(e.getKey());
            if (b == null) {
                unpaired++;
                continue;
            }
            final String[] instr = instructionsByLabel.get(baseQueryLabel(a.queryLabel()));
            if (instr == null) {
                LOGGER.warn("[judge] no query instruction found for row '{}' — skipped", a.queryLabel());
                unpaired++;
                continue;
            }
            if (delaySeconds > 0) {
                Thread.sleep(Duration.ofSeconds(delaySeconds).toMillis());
            }

            final ResponseQualityComparator.JudgeResult j = judgePair(judge, instr, a, b);
            final Double cos = embedder == null ? null : cosineOf(embedder, a, b);
            if (cos != null) {
                cosSum += cos;
                cosN++;
            }
            if (j != null) {
                verdicts.merge(j.verdict(), 1, Integer::sum);
                structASum += j.structureA();
                structBSum += j.structureB();
                judged++;
            }
            logRow(e.getKey(), cos, j, a, b);
            outRows.add(rowJson(e.getKey(), cos, j, a, b));
        }

        LOGGER.info("");
        LOGGER.info("  == judge aggregate: pairs judged {} (unpaired/skipped {}) | verdicts {} | mean structA {} structB {} | mean cos {}",
                judged, unpaired, verdicts,
                judged == 0 ? "n/a" : String.format("%.1f", (double) structASum / judged),
                judged == 0 ? "n/a" : String.format("%.1f", (double) structBSum / judged),
                cosN == 0 ? "n/a" : String.format("%.4f", cosSum / cosN));
        LOGGER.info("  == note: durations shown per row come from different runs/hardware — not a speed comparison");

        persist(fileA, fileB, modelA, modelB, judgeEntry, verdicts, outRows);
    }

    // ---- judging -------------------------------------------------------------------------------

    private static ResponseQualityComparator.JudgeResult judgePair(final ChatService judge, final String[] instr,
                                                                   final PersistedRow a, final PersistedRow b) {
        try {
            // Same content layout as the in-run comparator's model axis: identical instruction on
            // both sides (these runs share the query set), judged per its own instruction.
            final String content = "QUERY INSTRUCTION FOR ANSWER A (requested format):\n" + instr[1]
                    + "\n\nQUERY INSTRUCTION FOR ANSWER B (requested format):\n" + instr[1]
                    + "\n\nUSER QUERY FOR ANSWER A:\n" + instr[0]
                    + "\n\nUSER QUERY FOR ANSWER B:\n" + instr[0]
                    + "\n\nANSWER A:\n" + a.formatted()
                    + "\n\nANSWER B:\n" + b.formatted();
            return judge.callModel(ResponseQualityComparator.JUDGE_SYSTEM_PROMPT, content, String.class)
                    .map(ResponseQualityComparator::parseJudgeJson)
                    .orElse(null);
        } catch (final Exception e) {
            LOGGER.warn("[judge] judge call failed for {}: {}", a.queryLabel(), e.getMessage());
            return null;
        }
    }

    /** Judge construction mirrors TestHarness.buildService without touching its class init. */
    private static ChatService buildJudge(final String entry) {
        String spec = entry;
        String endpoint = "";
        final int at = spec.indexOf('@');
        if (at >= 0) {
            endpoint = spec.substring(at + 1).trim();
            spec = spec.substring(0, at).trim();
        }
        final int sep = spec.indexOf(':');
        final String provider = sep <= 0 ? "" : spec.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        final String deployment = sep <= 0 ? spec : spec.substring(sep + 1).trim();
        return switch (provider) {
            case "anthropic" -> new AnthropicChatService(deployment, endpoint);
            case "local" -> new LocalOpenAiChatService(deployment, endpoint);
            case "" -> ChatServiceFactory.getInstance(
                    endpoint.isEmpty() ? requireEnv("AZURE_OPENAI_ENDPOINT") : endpoint, deployment);
            default -> throw new IllegalStateException("Unsupported judge provider '" + provider
                    + "' in HARNESS_JUDGE_LLM (supported: anthropic:, local:, or no prefix)");
        };
    }

    // ---- optional local-embedding cosine ---------------------------------------------------------

    /** Minimal OpenAI-compatible /embeddings client for the cosine divergence flag. */
    private record LocalEmbedder(HttpClient http, String endpoint, String model) {

        static LocalEmbedder fromEnv(final String spec) {
            if (spec.isEmpty() || "off".equalsIgnoreCase(spec)) {
                return null;
            }
            if (!spec.toLowerCase(Locale.ROOT).startsWith("local:") || spec.indexOf('@') < 0) {
                throw new IllegalStateException("HARNESS_JUDGE_COSINE must be 'off' or local:<model>@<endpoint>, got: " + spec);
            }
            final String model = spec.substring("local:".length(), spec.indexOf('@'));
            final String endpoint = spec.substring(spec.indexOf('@') + 1);
            return new LocalEmbedder(HttpClient.newHttpClient(), endpoint, model);
        }

        List<Float> embed(final String text) throws Exception {
            final Map<String, Object> body = Map.of("model", model, "input", List.of(text));
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/embeddings"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + env("HARNESS_LOCAL_LLM_API_KEY", "lm-studio"))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("local embeddings HTTP " + resp.statusCode());
            }
            final JsonNode vector = MAPPER.readTree(resp.body()).get("data").get(0).get("embedding");
            final List<Float> out = new ArrayList<>(vector.size());
            vector.forEach(v -> out.add((float) v.asDouble()));
            return out;
        }
    }

    private static Double cosineOf(final LocalEmbedder embedder, final PersistedRow a, final PersistedRow b) {
        try {
            final String proseA = CitationMetrics.proseOf(a.raw());
            final String proseB = CitationMetrics.proseOf(b.raw());
            if (proseA.isEmpty() || proseB.isEmpty()) {
                return null;
            }
            return cosineSimilarity(embedder.embed(proseA), embedder.embed(proseB));
        } catch (final Exception e) {
            LOGGER.warn("[judge] cosine failed for {}: {}", a.queryLabel(), e.getMessage());
            return null;
        }
    }

    // ---- run-file + query-file loading -----------------------------------------------------------

    /** Rows keyed by (queryLabel|promptLabel|iteration) for one model, ANSWER_GENERATED only. */
    private static Map<String, PersistedRow> rowsOf(final JsonNode run, final String model) {
        final Map<String, PersistedRow> out = new LinkedHashMap<>();
        for (final JsonNode r : run.get("results")) {
            if (!model.equals(r.get("llmLabel").asText())
                    || !r.hasNonNull("status") || !"ANSWER_GENERATED".equals(r.get("status").asText())
                    || r.hasNonNull("error")) {
                continue;
            }
            final PersistedRow row = new PersistedRow(
                    r.get("queryLabel").asText(), r.get("promptLabel").asText(), r.get("iteration").asInt(),
                    r.get("durationMs").asLong(), r.get("rawResponse").asText(), r.get("formattedResponse").asText());
            out.put(row.queryLabel() + "|" + row.promptLabel() + "|" + row.iteration(), row);
        }
        return out;
    }

    /** The single llmLabel in the file, or the explicitly requested one (validated). */
    private static String selectModel(final JsonNode run, final String requested, final String side) {
        final List<String> models = new ArrayList<>();
        run.get("results").forEach(r -> {
            final String m = r.get("llmLabel").asText();
            if (!models.contains(m)) {
                models.add(m);
            }
        });
        if (!requested.isEmpty()) {
            if (!models.contains(requested)) {
                throw new IllegalStateException("HARNESS_JUDGE_MODEL_" + side + "='" + requested
                        + "' not present in file (has: " + models + ")");
            }
            return requested;
        }
        if (models.size() != 1) {
            throw new IllegalStateException("File " + side + " contains " + models
                    + " — set HARNESS_JUDGE_MODEL_" + side + " to pick one");
        }
        return models.get(0);
    }

    /**
     * label → [userQuery, queryPrompt] from the query-set file. Multi-version files key each
     * version's instruction under "label #version" (matching the run rows' label suffix).
     */
    private static Map<String, String[]> loadInstructions(final String queryFile) throws Exception {
        final JsonNode root = readQueryFile(queryFile);
        final List<JsonNode> versions = new ArrayList<>();
        root.get("versions").forEach(versions::add);
        final Map<String, String[]> out = new LinkedHashMap<>();
        for (final JsonNode versionNode : versions) {
            final String version = versionNode.get("version").asText();
            for (final JsonNode q : versionNode.get("queries")) {
                final String key = versions.size() > 1
                        ? q.get("label").asText() + " #" + version
                        : q.get("label").asText();
                out.put(key, new String[] {q.get("userQuery").asText(), q.get("queryPrompt").asText()});
            }
        }
        return out;
    }

    /** Run-row label without the multi-document {@code @<id8>} infix: "label @id8[ #v]" → "label[ #v]". */
    static String baseQueryLabel(final String rowLabel) {
        return rowLabel.replaceFirst(" @\\w{1,8}", "");
    }

    private static JsonNode readQueryFile(final String queryFile) throws Exception {
        final Path[] candidates = {
                Paths.get(MODULE_DIR_NAME + "/src/main/resources/" + QUERY_FILE_DIR + "/" + queryFile),
                Paths.get("src/main/resources/" + QUERY_FILE_DIR + "/" + queryFile)
        };
        for (final Path p : candidates) {
            if (Files.exists(p)) {
                return MAPPER.readTree(p.toFile());
            }
        }
        try (InputStream in = BaselineJudgeTool.class.getResourceAsStream("/" + QUERY_FILE_DIR + "/" + queryFile)) {
            if (in == null) {
                throw new IllegalStateException(queryFile + " not found under src/main/resources/" + QUERY_FILE_DIR);
            }
            return MAPPER.readTree(in);
        }
    }

    private static Path resolve(final String file) {
        final Path given = Paths.get(file);
        if (Files.exists(given)) {
            return given;
        }
        final Path[] candidates = {
                Paths.get(MODULE_DIR_NAME, RESULTS_DIR, file),
                Paths.get(RESULTS_DIR, file),
                Paths.get(MODULE_DIR_NAME).resolve(file),
        };
        for (final Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("Run file not found: " + file);
    }

    // ---- output ----------------------------------------------------------------------------------

    private static void logRow(final String key, final Double cos,
                               final ResponseQualityComparator.JudgeResult j,
                               final PersistedRow a, final PersistedRow b) {
        LOGGER.info(String.format("%-44s | cos %-6s | %-10s | missA %s missB %s | judgeStruct A%s B%s | mdStruct A %-8s B %-8s | %s",
                truncate(key, 44),
                cos == null ? "n/a" : String.format("%.4f", cos),
                j == null ? "n/a" : j.verdict(),
                j == null ? "-" : j.missingFromA(), j == null ? "-" : j.missingFromB(),
                j == null ? "-" : j.structureA(), j == null ? "-" : j.structureB(),
                ResponseQualityComparator.structureOf(a.formatted()),
                ResponseQualityComparator.structureOf(b.formatted()),
                j == null ? "" : j.note()));
    }

    private static Map<String, Object> rowJson(final String key, final Double cos,
                                               final ResponseQualityComparator.JudgeResult j,
                                               final PersistedRow a, final PersistedRow b) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("row", key);
        row.put("cosine", cos);
        if (j != null) {
            row.put("verdict", j.verdict());
            row.put("missingFromA", j.missingFromA());
            row.put("missingFromB", j.missingFromB());
            row.put("structureA", j.structureA());
            row.put("structureB", j.structureB());
            row.put("note", j.note());
        }
        row.put("durationMsA", a.durationMs());
        row.put("durationMsB", b.durationMs());
        return row;
    }

    private static void persist(final Path fileA, final Path fileB, final String modelA, final String modelB,
                                final String judgeEntry, final Map<String, Integer> verdicts,
                                final List<Map<String, Object>> rows) throws Exception {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("judge", judgeEntry);
        meta.put("fileA", fileA.toString());
        meta.put("fileB", fileB.toString());
        meta.put("modelA", modelA);
        meta.put("modelB", modelB);
        meta.put("verdictTotals", verdicts);

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("rows", rows);

        final Path dir = Files.isDirectory(Paths.get(MODULE_DIR_NAME))
                ? Paths.get(MODULE_DIR_NAME, RESULTS_DIR) : Paths.get(RESULTS_DIR);
        Files.createDirectories(dir);
        final Path file = dir.resolve("judge-"
                + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        LOGGER.info("[judge] persisted {} judged rows to {}", rows.size(), file.toAbsolutePath());
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
