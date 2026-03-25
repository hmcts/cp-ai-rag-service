package uk.gov.moj.cp.retrieval.service;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Double.parseDouble;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                    .anyMatch(existing -> calculateCosineSimilarity(
                            incoming.chunkVector(), existing.chunkVector()) >= threshold);
            if (!isDuplicate) {
                uniqueEntries.add(incoming);
            }
        }
        LOGGER.info("After filtering with threshold '{}', {} chunk entries remains", threshold, uniqueEntries.size());
        return uniqueEntries;
    }

    /**
     * See https://help.openai.com/en/articles/6824809-embeddings-faq
     * <p>
     * Mathematical Impact: OpenAI embeddings are normalized to length 1 and because the vectors are
     * unit-length, Cosine similarity can be computed slightly faster using just a dot product.
     *
     * @param vecA
     * @param vecB
     * @return
     */
    private double calculateCosineSimilarity(final List<Float> vecA, final List<Float> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        for (int i = 0; i < vecA.size(); i++) {
            dotProduct += vecA.get(i) * vecB.get(i);
        }

        return dotProduct;
    }
}
