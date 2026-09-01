package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.harness.HarnessEnv.env;
import static uk.gov.moj.cp.harness.HarnessEnv.intEnv;
import static uk.gov.moj.cp.harness.HarnessEnv.requireEnv;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.EmbeddingService;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the retrieval half of the harness pipeline — query embedding → AI Search →
 * containment-dedup → MMR — into a local JSON snapshot, so later evaluation runs can replay
 * the exact chunks (and their vectors) without access to the Azure embedding or search
 * services. The generation half (LLM calls) is untouched: a snapshot fixes the LLM *input*,
 * making offline model comparisons byte-identical to a live run on the retrieval side.
 *
 * <p>Retrieval depends only on (userQuery, documentId) — not on the query prompt, version or
 * system prompt — so the snapshot deduplicates on that pair: each distinct query text is
 * embedded once and searched once per document. The {@code rows} section maps every
 * (queryId, version, documentId) cell of the harness matrix back to its retrieval.
 *
 * <p>Snapshot layout (single JSON file):
 * <ul>
 *   <li>{@code meta} — capture time, query file, document ids, embedding deployment, search
 *       index and the retrieval-tuning env values that shaped the result (provenance: a replay
 *       against a snapshot captured with different tuning is a different experiment).</li>
 *   <li>{@code queryEmbeddings} — distinct userQuery text → embedding vector.</li>
 *   <li>{@code chunks} — chunk id → full {@link ChunkedEntry} (content, page, file name,
 *       metadata, chunkVector), stored once each; the same document's chunks recur across many
 *       queries, so this keeps the file a fraction of the denormalised size.</li>
 *   <li>{@code retrievals} — one per (userQuery, documentId): the post-refinement chunk ids in
 *       rank order (the order the LLM sees — it must be preserved exactly).</li>
 *   <li>{@code rows} — (queryId, version, documentId) → retrieval, for traceability.</li>
 * </ul>
 *
 * <p><b>Sensitivity:</b> chunks are verbatim case-document content — the snapshot directory is
 * git-ignored and snapshots must never be committed, same rule as {@code idpc-documents/}.
 *
 * <p>Run via {@code capture-retrieval-snapshot.sh} (module root), which exports {@code .env}
 * and launches this class. Configuration: {@code HARNESS_QUERY_FILE} (query set to capture,
 * default {@code user-queries-version-test.json}), {@code HARNESS_DOCUMENT_IDS} (required),
 * {@code HARNESS_MAX_QUERIES} (optional cap, same semantics as the harness), and
 * {@code HARNESS_SNAPSHOT_DIR} (output directory, default {@code retrieval-snapshots/} under
 * the module). Requires {@code az login} for the embedding/search calls; makes no LLM calls.
 */
public final class RetrievalSnapshotTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrievalSnapshotTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Metadata field the AI Search index filters documents on (mirrors TestHarness). */
    private static final String DOCUMENT_ID_FILTER_KEY = "document_id";

    private static final String QUERY_FILE_DIR = "user-queries";
    private static final String DEFAULT_QUERY_FILE = "user-queries-version-test.json";
    private static final String DEFAULT_SNAPSHOT_DIR = "retrieval-snapshots";
    private static final String MODULE_DIR_NAME = "ai-document-system-prompt-harness-eval";

    /** Env keys recorded in the snapshot header — the knobs that shape what retrieval returns. */
    private static final List<String> TUNING_ENV_KEYS = List.of(
            "SEARCH_NEAREST_NEIGHBOURS_COUNT", "SEARCH_TOP_RESULTS_COUNT",
            "SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP", "SEARCH_CONTAINMENT_SHINGLE_SIZE",
            "SEARCH_CONTAINMENT_THRESHOLD", "SEARCH_RESULTS_ENABLE_DEDUPLICATION",
            "SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD", "SEARCH_RESULTS_ENABLE_MMR",
            "SEARCH_MMR_LAMBDA", "SEARCH_MMR_FINAL_COUNT");

    private RetrievalSnapshotTool() {
    }

    /** One (queryId, version) entry of the query set, before document expansion. */
    record QueryEntry(String queryId, String version, String label, String userQuery) {
    }

    /** One captured retrieval: the ranked chunk ids for a (userQuery, documentId) pair. */
    record Retrieval(String userQuery, String documentId, List<String> chunkIds, long durationMs) {
    }

    /** One harness matrix cell mapped to its retrieval (rows share retrievals across versions). */
    record Row(String queryId, String version, String label, String documentId, String userQuery) {
    }

    public static void main(final String[] args) throws Exception {
        final String queryFile = env("HARNESS_QUERY_FILE", DEFAULT_QUERY_FILE);
        final List<String> documentIds = splitCsv(requireEnv("HARNESS_DOCUMENT_IDS"));
        final List<QueryEntry> entries = loadQueryEntries(queryFile);

        // Retrieval is a function of (userQuery, documentId) only, so capture each distinct
        // query text once — prod/test versions mostly share userQuery and would otherwise
        // re-run identical searches.
        final Set<String> distinctQueries = new LinkedHashSet<>();
        entries.forEach(e -> distinctQueries.add(e.userQuery()));
        LOGGER.info("[capture] {} query entries ({} distinct userQuery texts) x {} documents = {} retrievals",
                entries.size(), distinctQueries.size(), documentIds.size(),
                distinctQueries.size() * documentIds.size());

        // Pinned to a GA api-version: the platform's hardened OpenAI resources 401 the SDK's
        // default preview api-version. See PinnedApiVersionEmbeddingService.
        final EmbeddingService embeddingService = new PinnedApiVersionEmbeddingService(
                requireEnv("AZURE_EMBEDDING_SERVICE_ENDPOINT"), requireEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME"));
        final AzureAISearchService searchService = new AzureAISearchService(
                requireEnv("AZURE_SEARCH_SERVICE_ENDPOINT"), requireEnv("AZURE_SEARCH_SERVICE_INDEX_NAME"));

        // Any failed retrieval fails the whole capture: a partial snapshot would surface as
        // silently skipped cells at replay time, and re-running the capture is cheap.
        final Map<String, List<Float>> queryEmbeddings = new LinkedHashMap<>();
        for (final String userQuery : distinctQueries) {
            queryEmbeddings.put(userQuery, embeddingService.embedData(userQuery));
        }
        LOGGER.info("[capture] embedded {} distinct queries", queryEmbeddings.size());

        final Map<String, ChunkedEntry> chunksById = new LinkedHashMap<>();
        final List<Retrieval> retrievals = new ArrayList<>();
        for (final String userQuery : distinctQueries) {
            for (final String documentId : documentIds) {
                final long t0 = System.currentTimeMillis();
                final List<ChunkedEntry> chunks = searchService.search(null, userQuery,
                        queryEmbeddings.get(userQuery),
                        List.of(new KeyValuePair(DOCUMENT_ID_FILTER_KEY, documentId)));
                final long ms = System.currentTimeMillis() - t0;
                final List<String> chunkIds = new ArrayList<>();
                for (final ChunkedEntry chunk : chunks) {
                    chunkIds.add(chunk.id());
                    chunksById.putIfAbsent(chunk.id(), chunk);
                }
                retrievals.add(new Retrieval(userQuery, documentId, chunkIds, ms));
                LOGGER.info("[capture] doc={} -> {} chunks in {} ms | query: {}",
                        shortId(documentId), chunks.size(), ms, truncate(userQuery, 60));
            }
        }

        final List<Row> rows = new ArrayList<>();
        for (final QueryEntry e : entries) {
            for (final String documentId : documentIds) {
                rows.add(new Row(e.queryId(), e.version(), e.label(), documentId, e.userQuery()));
            }
        }

        final Path out = writeSnapshot(queryFile, documentIds, queryEmbeddings, chunksById, retrievals, rows);
        verifySnapshot(out);
        LOGGER.info("[capture] DONE: {} ({} MB) — {} retrievals, {} distinct chunks, {} rows",
                out.toAbsolutePath(), Files.size(out) / (1024 * 1024),
                retrievals.size(), chunksById.size(), rows.size());
    }

    // ---- query-set loading (same file resolution as TestHarness, without its statics) ---------

    /**
     * Flattens every (version × query) of the query set. HARNESS_MAX_QUERIES caps the base
     * queryIds before version expansion, matching the harness's semantics, so a capped capture
     * still covers a capped harness run exactly.
     */
    private static List<QueryEntry> loadQueryEntries(final String queryFile) throws Exception {
        final JsonNode root = readQueryFile(queryFile);
        final List<JsonNode> versions = new ArrayList<>();
        root.get("versions").forEach(versions::add);

        final JsonNode baseQueries = versions.get(0).get("queries");
        final int maxQueries = intEnv("HARNESS_MAX_QUERIES", 0);
        final int limit = (maxQueries > 0 && maxQueries < baseQueries.size()) ? maxQueries : baseQueries.size();
        final Set<String> includedIds = new LinkedHashSet<>();
        for (int i = 0; i < limit; i++) {
            includedIds.add(baseQueries.get(i).get("queryId").asText());
        }

        final List<QueryEntry> out = new ArrayList<>();
        for (final JsonNode versionNode : versions) {
            final String version = versionNode.get("version").asText();
            for (final JsonNode q : versionNode.get("queries")) {
                if (includedIds.contains(q.get("queryId").asText())) {
                    out.add(new QueryEntry(q.get("queryId").asText(), version,
                            q.get("label").asText(), q.get("userQuery").asText()));
                }
            }
        }
        LOGGER.info("[capture] loaded {} ({} versions, {} of {} base queries)",
                queryFile, versions.size(), limit, baseQueries.size());
        return out;
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
        try (InputStream in = RetrievalSnapshotTool.class.getResourceAsStream("/" + QUERY_FILE_DIR + "/" + queryFile)) {
            if (in == null) {
                throw new IllegalStateException(queryFile + " not found under src/main/resources/"
                        + QUERY_FILE_DIR + " (filesystem or classpath)");
            }
            return MAPPER.readTree(in);
        }
    }

    // ---- snapshot writing ----------------------------------------------------------------------

    private static Path writeSnapshot(final String queryFile, final List<String> documentIds,
                                      final Map<String, List<Float>> queryEmbeddings,
                                      final Map<String, ChunkedEntry> chunksById,
                                      final List<Retrieval> retrievals, final List<Row> rows) throws Exception {
        final Map<String, String> tuning = new LinkedHashMap<>();
        for (final String key : TUNING_ENV_KEYS) {
            tuning.put(key, env(key, ""));
        }

        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("capturedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("queryFile", queryFile);
        meta.put("documentIds", documentIds);
        meta.put("embeddingDeployment", requireEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME"));
        meta.put("searchIndex", requireEnv("AZURE_SEARCH_SERVICE_INDEX_NAME"));
        meta.put("retrievalTuning", tuning);

        final Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("meta", meta);
        snapshot.put("queryEmbeddings", queryEmbeddings);
        snapshot.put("chunks", chunksById);
        snapshot.put("retrievals", retrievals);
        snapshot.put("rows", rows);

        final Path dir = resolveSnapshotDir();
        Files.createDirectories(dir);
        final String stem = queryFile.endsWith(".json") ? queryFile.substring(0, queryFile.length() - 5) : queryFile;
        final Path out = dir.resolve("retrieval-snapshot-" + stem + "-"
                + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        MAPPER.writeValue(out.toFile(), snapshot);
        return out;
    }

    /** Module-local snapshot dir regardless of whether the JVM started at the repo or module root. */
    private static Path resolveSnapshotDir() {
        final String override = env("HARNESS_SNAPSHOT_DIR", "");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        return Files.isDirectory(Paths.get(MODULE_DIR_NAME))
                ? Paths.get(MODULE_DIR_NAME, DEFAULT_SNAPSHOT_DIR)
                : Paths.get(DEFAULT_SNAPSHOT_DIR);
    }

    /**
     * Round-trips the written file and cross-checks referential integrity — every retrieval's
     * chunk ids resolve, every row has a retrieval, every query embedding is non-empty — so a
     * bad snapshot fails here, at capture time, not weeks later at replay time.
     */
    private static void verifySnapshot(final Path file) throws Exception {
        final JsonNode root = MAPPER.readTree(file.toFile());
        final Set<String> chunkIds = new LinkedHashSet<>();
        root.get("chunks").fieldNames().forEachRemaining(chunkIds::add);

        int emptyVectors = 0;
        for (final JsonNode chunk : root.get("chunks")) {
            // Field name per IndexConstants.CHUNK_VECTOR ("chunkVector"), via ChunkedEntry's @JsonProperty.
            final JsonNode vector = chunk.get("chunkVector");
            if (vector == null || !vector.isArray() || vector.isEmpty()) {
                emptyVectors++;
            }
        }

        final Set<String> retrievalKeys = new LinkedHashSet<>();
        for (final JsonNode retrieval : root.get("retrievals")) {
            retrievalKeys.add(retrieval.get("userQuery").asText() + "|" + retrieval.get("documentId").asText());
            for (final JsonNode id : retrieval.get("chunkIds")) {
                if (!chunkIds.contains(id.asText())) {
                    throw new IllegalStateException("Snapshot corrupt: retrieval references unknown chunk id " + id.asText());
                }
            }
        }
        for (final JsonNode row : root.get("rows")) {
            final String key = row.get("userQuery").asText() + "|" + row.get("documentId").asText();
            if (!retrievalKeys.contains(key)) {
                throw new IllegalStateException("Snapshot corrupt: row " + row.get("label").asText()
                        + " (" + row.get("version").asText() + ") has no retrieval");
            }
        }
        for (final JsonNode vector : root.get("queryEmbeddings")) {
            if (!vector.isArray() || vector.isEmpty()) {
                throw new IllegalStateException("Snapshot corrupt: empty query embedding");
            }
        }
        LOGGER.info("[verify] OK: {} chunks ({} without vectors), {} retrievals, {} rows, {} query embeddings",
                chunkIds.size(), emptyVectors, retrievalKeys.size(), root.get("rows").size(),
                root.get("queryEmbeddings").size());
        if (emptyVectors > 0) {
            LOGGER.warn("[verify] {} chunks have no chunk_vector — offline stages needing vectors would skip them", emptyVectors);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static List<String> splitCsv(final String csv) {
        final List<String> out = new ArrayList<>();
        for (final String token : csv.split(",")) {
            if (!token.trim().isEmpty()) {
                out.add(token.trim());
            }
        }
        return out;
    }

    private static String shortId(final String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
