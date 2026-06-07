package uk.gov.moj.cp.retrieval.service.filter;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Double.parseDouble;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.VectorSimilarityUtil.cosineSimilarity;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diversifies search results using Maximal Marginal Relevance (MMR).
 * <p>
 * Azure AI Search has no server-side diversity/dedup operator, so this runs as a post-retrieval step:
 * given an over-fetched candidate pool, it greedily selects a smaller set that balances relevance to the
 * query against novelty versus already-selected chunks. This suppresses the duplicated bulk shared across
 * related documents, surfaces the unique chunks, and reduces the number of tokens sent to the LLM.
 */
public class DiversificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiversificationService.class);

    public static final String SEARCH_RESULTS_ENABLE_MMR = "SEARCH_RESULTS_ENABLE_MMR";
    /**
     * Env var name for the MMR trade-off weight, a value in [0.0, 1.0] applied per pick as
     * {@code lambda * relevance - (1 - lambda) * maxSimilarityToSelected}. Higher values (towards 1.0)
     * favour relevance to the query and tolerate redundancy; lower values (towards 0.0) favour
     * diversity, more aggressively suppressing near-duplicate chunks. Defaults to 0.5 (balanced).
     */
    private static final String SEARCH_MMR_LAMBDA = "SEARCH_MMR_LAMBDA";
    private static final String SEARCH_MMR_FINAL_COUNT = "SEARCH_MMR_FINAL_COUNT";

    private final boolean enableMmr;
    private final double lambda;
    private final int finalCount;

    public DiversificationService() {
        this.enableMmr = parseBoolean(getRequiredEnv(SEARCH_RESULTS_ENABLE_MMR, "false"));
        this.lambda = parseDouble(getRequiredEnv(SEARCH_MMR_LAMBDA, "0.5"));
        this.finalCount = getRequiredEnvAsInteger(SEARCH_MMR_FINAL_COUNT, "15");
    }

    public DiversificationService(final double lambda, final int finalCount, final boolean enableMmr) {
        this.lambda = lambda;
        this.finalCount = finalCount;
        this.enableMmr = enableMmr;
    }

    /**
     * Selects a diverse, relevance-aware subset of the candidates using MMR.
     * <p>
     * For each pick: {@code score(c) = lambda * relevance(c, query) - (1 - lambda) * max similarity(c, selected)}.
     * Higher {@code lambda} favours relevance; lower {@code lambda} favours diversity.
     *
     * @param queryVector the embedded user query (already a parameter of the search call)
     * @param candidates  the over-fetched result pool, each carrying its {@code chunkVector}
     * @return up to {@code finalCount} chunks; the input unchanged when MMR is disabled or inputs are empty
     */
    public List<ChunkedEntry> diversify(final List<Float> queryVector, final List<ChunkedEntry> candidates) {
        if (!enableMmr) {
            return candidates;
        }
        if (isNullOrEmpty(queryVector) || isNullOrEmpty(candidates)) {
            LOGGER.warn("MMR enabled but query vector or candidates are null/empty; returning candidates unchanged");
            return candidates;
        }

        final int targetCount = Math.min(finalCount, candidates.size());
        final Map<ChunkedEntry, Double> relevanceByCandidate = relevanceByCandidate(queryVector, candidates);

        final List<ChunkedEntry> remaining = new ArrayList<>(candidates);
        final List<ChunkedEntry> selected = new ArrayList<>(targetCount);

        while (selected.size() < targetCount && !remaining.isEmpty()) {
            final ChunkedEntry best = selectBest(remaining, selected, relevanceByCandidate);
            selected.add(best);
            remaining.remove(best);
        }

        LOGGER.info("MMR diversification (lambda={}) reduced {} candidates to {} chunks", lambda, candidates.size(), selected.size());
        return selected;
    }

    /**
     * Precomputes relevance(query, candidate) once per candidate. An {@link IdentityHashMap} avoids hashing
     * the large {@code chunkVector} lists that {@link ChunkedEntry}'s record equals/hashCode would traverse.
     */
    private Map<ChunkedEntry, Double> relevanceByCandidate(final List<Float> queryVector, final List<ChunkedEntry> candidates) {
        final Map<ChunkedEntry, Double> relevanceByCandidate = new IdentityHashMap<>();
        for (final ChunkedEntry candidate : candidates) {
            relevanceByCandidate.put(candidate, cosineSimilarity(queryVector, candidate.chunkVector()));
        }
        return relevanceByCandidate;
    }

    /** Returns the remaining candidate with the highest MMR score against the already-selected set. */
    private ChunkedEntry selectBest(final List<ChunkedEntry> remaining, final List<ChunkedEntry> selected,
                                    final Map<ChunkedEntry, Double> relevanceByCandidate) {
        ChunkedEntry best = null;
        double bestScore = -Double.MAX_VALUE;
        for (final ChunkedEntry candidate : remaining) {
            final double score = mmrScore(candidate, selected, relevanceByCandidate.get(candidate));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** {@code lambda * relevance - (1 - lambda) * maxSimilarityToSelected}; pure relevance for the first pick. */
    private double mmrScore(final ChunkedEntry candidate, final List<ChunkedEntry> selected, final double relevance) {
        if (selected.isEmpty()) {
            return relevance;
        }
        return lambda * relevance - (1 - lambda) * maxSimilarityToSelected(candidate, selected);
    }

    private double maxSimilarityToSelected(final ChunkedEntry candidate, final List<ChunkedEntry> selected) {
        double maxSimilarity = -Double.MAX_VALUE;
        for (final ChunkedEntry chosen : selected) {
            maxSimilarity = Math.max(maxSimilarity, cosineSimilarity(candidate.chunkVector(), chosen.chunkVector()));
        }
        return maxSimilarity;
    }

    private static boolean isNullOrEmpty(final List<?> list) {
        return list == null || list.isEmpty();
    }
}
