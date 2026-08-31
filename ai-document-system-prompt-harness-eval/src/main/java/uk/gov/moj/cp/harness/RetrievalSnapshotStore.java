package uk.gov.moj.cp.harness;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a retrieval snapshot written by {@link RetrievalSnapshotTool} and rebuilds the
 * {@code queryLabel -> chunks} map that {@link TestHarness} otherwise assembles from live
 * Azure embedding + AI Search calls. Activated by {@code HARNESS_RETRIEVAL_SNAPSHOT} (path to
 * the snapshot file): a replayed run feeds the generation pipeline byte-identical chunk input
 * — same {@link ChunkedEntry} objects in the same post-refinement rank order — with no
 * dependency on the embedding or search services for retrieval.
 *
 * <p>Lookups join on (userQuery, documentId), the pair that fully determines a retrieval, so a
 * snapshot serves any query set whose texts it covers (e.g. one captured from the version-test
 * set also serves the model-test set, whose queries are a subset). A row with no matching
 * retrieval fails the load — a partial replay would silently skip harness cells.
 */
final class RetrievalSnapshotStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrievalSnapshotStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RetrievalSnapshotStore() {
    }

    static Map<String, List<ChunkedEntry>> load(final String snapshotFile,
                                                final List<TestHarness.UserQueryConfig> queries,
                                                final String currentQueryFile) {
        try {
            final JsonNode root = MAPPER.readTree(resolve(snapshotFile).toFile());

            final JsonNode meta = root.get("meta");
            final String capturedQueryFile = meta.get("queryFile").asText();
            if (!capturedQueryFile.equals(currentQueryFile)) {
                // Not fatal: the (userQuery, documentId) join below decides compatibility. The
                // per-row check catches any genuine gap.
                LOGGER.warn("[snapshot] captured from query file '{}' but this run uses '{}' — "
                        + "proceeding, every row is still checked for a matching retrieval",
                        capturedQueryFile, currentQueryFile);
            }

            final Map<String, ChunkedEntry> chunksById = new LinkedHashMap<>();
            root.get("chunks").fields().forEachRemaining(e ->
                    chunksById.put(e.getKey(), MAPPER.convertValue(e.getValue(), ChunkedEntry.class)));

            final Map<String, List<ChunkedEntry>> byQueryAndDocument = new LinkedHashMap<>();
            for (final JsonNode retrieval : root.get("retrievals")) {
                final List<ChunkedEntry> chunks = new ArrayList<>();
                for (final JsonNode id : retrieval.get("chunkIds")) {
                    final ChunkedEntry chunk = chunksById.get(id.asText());
                    if (chunk == null) {
                        throw new IllegalStateException("Snapshot corrupt: retrieval references unknown chunk id " + id.asText());
                    }
                    chunks.add(chunk);
                }
                byQueryAndDocument.put(
                        retrieval.get("userQuery").asText() + "|" + retrieval.get("documentId").asText(), chunks);
            }

            final Map<String, List<ChunkedEntry>> chunksByQueryLabel = new LinkedHashMap<>();
            final List<String> missing = new ArrayList<>();
            for (final TestHarness.UserQueryConfig uqc : queries) {
                final List<ChunkedEntry> chunks = byQueryAndDocument.get(uqc.userQuery() + "|" + uqc.documentId());
                if (chunks == null) {
                    missing.add(uqc.label());
                } else {
                    chunksByQueryLabel.put(uqc.label(), chunks);
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Snapshot " + snapshotFile + " (captured " + meta.get("capturedAt").asText()
                        + " from '" + capturedQueryFile + "') has no retrieval for " + missing.size()
                        + " of " + queries.size() + " rows: " + missing
                        + " — re-run capture-retrieval-snapshot.sh for the current query set / document ids");
            }

            LOGGER.info("[snapshot] replaying retrieval from {} (captured {}): {} rows resolved from {} retrievals / {} chunks",
                    snapshotFile, meta.get("capturedAt").asText(), chunksByQueryLabel.size(),
                    byQueryAndDocument.size(), chunksById.size());
            return chunksByQueryLabel;
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to load retrieval snapshot " + snapshotFile, e);
        }
    }

    /** Resolves a module-relative path whether the JVM started at the repo or the module root. */
    private static java.nio.file.Path resolve(final String snapshotFile) {
        final java.nio.file.Path given = Paths.get(snapshotFile);
        if (java.nio.file.Files.exists(given)) {
            return given;
        }
        return Paths.get("ai-document-system-prompt-harness-eval").resolve(snapshotFile);
    }
}
