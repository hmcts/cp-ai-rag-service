package uk.gov.moj.cp.retrieval.service.filter;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Double.parseDouble;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.VectorSimilarityUtil.cosineSimilarity;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic deduplication of retrieved chunks based on embedding similarity.
 * <p>
 * Runs at retrieval time and, in ranked order, drops any chunk whose {@code chunkVector} is at least
 * {@code SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD} cosine-similar to a chunk already kept,
 * retaining the highest-ranked representative of each near-duplicate group. Gated by
 * {@code SEARCH_RESULTS_ENABLE_DEDUPLICATION} (off by default).
 * <p>
 * Note: this is a symmetric similarity test, so it can discard a near-duplicate that actually carries
 * unique information. For information-safe collapsing use {@link ContentContainmentService}; this path
 * is retained as a coarse, optional similarity filter.
 */
public class DeduplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeduplicationService.class);

    public static final String SEARCH_RESULTS_ENABLE_DEDUPLICATION = "SEARCH_RESULTS_ENABLE_DEDUPLICATION";
    private static final String SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD = "SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD";

    private final double threshold;
    private final boolean enableDeduplication;

    public DeduplicationService() {
        threshold = parseDouble(getRequiredEnv(SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD, ".95"));
        enableDeduplication = parseBoolean(getRequiredEnv(SEARCH_RESULTS_ENABLE_DEDUPLICATION, "false"));
    }

    public DeduplicationService(final double threshold, final boolean enableDeduplication) {
        this.threshold = threshold;
        this.enableDeduplication = enableDeduplication;
    }

    public List<ChunkedEntry> performSemanticDeduplication(final List<ChunkedEntry> entries) {
        if (!enableDeduplication) {
            return entries;
        }

        final List<ChunkedEntry> uniqueEntries = new ArrayList<>();
        for (final ChunkedEntry incoming : entries) {
            final boolean isDuplicate = uniqueEntries.stream()
                    .anyMatch(existing -> cosineSimilarity(
                            incoming.chunkVector(), existing.chunkVector()) >= threshold);
            if (!isDuplicate) {
                uniqueEntries.add(incoming);
            }
        }
        LOGGER.info("After filtering with threshold '{}', {} chunk entries remains", threshold, uniqueEntries.size());
        return uniqueEntries;
    }
}
