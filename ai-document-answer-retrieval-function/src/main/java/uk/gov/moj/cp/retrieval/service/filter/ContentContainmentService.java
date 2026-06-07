package uk.gov.moj.cp.retrieval.service.filter;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Double.parseDouble;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Information-safe deduplication of retrieved chunks based on textual <em>containment</em>.
 * <p>
 * The same passage often appears across genuinely different files, so it cannot be deduplicated at
 * ingestion (provenance and per-file filtering must be preserved). This runs at retrieval time and
 * collapses those copies <em>without losing unique information</em>: a chunk is dropped only when
 * (nearly) all of its content already appears in a higher-ranked, retained chunk.
 * <p>
 * Containment is asymmetric — {@code containment(A in B) = |shingles(A) ∩ shingles(B)| / |shingles(A)|}
 * — and that is the whole point. A plain copy is fully contained in a "copy + crucial sentence"
 * superset, so the plain copy drops and the superset (with the extra information) is kept. Two chunks
 * carrying <em>different</em> extra facts contain each other only partially, so both are kept. Unlike
 * cosine similarity, this never silently discards a chunk that says something the retained set does not.
 */
public class ContentContainmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentContainmentService.class);

    public static final String SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP = "SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP";
    private static final String SEARCH_CONTAINMENT_SHINGLE_SIZE = "SEARCH_CONTAINMENT_SHINGLE_SIZE";
    private static final String SEARCH_CONTAINMENT_THRESHOLD = "SEARCH_CONTAINMENT_THRESHOLD";

    private static final String NON_ALPHANUMERIC = "[^\\p{Alnum}]+";

    private final boolean enableContainmentDedup;
    private final int shingleSize;
    private final double threshold;

    public ContentContainmentService() {
        this.enableContainmentDedup = parseBoolean(getRequiredEnv(SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP, "false"));
        this.shingleSize = getRequiredEnvAsInteger(SEARCH_CONTAINMENT_SHINGLE_SIZE, "3");
        this.threshold = parseDouble(getRequiredEnv(SEARCH_CONTAINMENT_THRESHOLD, "0.95"));
    }

    public ContentContainmentService(final int shingleSize, final double threshold, final boolean enableContainmentDedup) {
        this.shingleSize = shingleSize;
        this.threshold = threshold;
        this.enableContainmentDedup = enableContainmentDedup;
    }

    /**
     * Drops chunks whose content is (nearly) fully contained in an earlier, retained chunk.
     * Input order is preserved (callers pass results in relevance order, so the highest-ranked
     * representative of any duplicate group is the one kept).
     *
     * @param entries retrieved chunks in relevance order
     * @return the retained chunks; the input unchanged when disabled or when there is nothing to compare
     */
    public List<ChunkedEntry> deduplicateByContainment(final List<ChunkedEntry> entries) {
        if (!enableContainmentDedup) {
            return entries;
        }
        if (entries == null || entries.size() <= 1) {
            return entries;
        }

        final List<ChunkedEntry> retained = new ArrayList<>();
        final List<Set<String>> retainedShingles = new ArrayList<>();

        for (final ChunkedEntry incoming : entries) {
            final Set<String> incomingShingles = shingles(incoming.chunk());

            boolean covered = false;
            for (final Set<String> existing : retainedShingles) {
                if (containment(incomingShingles, existing) >= threshold) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                retained.add(incoming);
                retainedShingles.add(incomingShingles);
            }
        }

        LOGGER.info("Containment dedup (shingleSize={}, threshold={}) reduced {} chunks to {}",
                shingleSize, threshold, entries.size(), retained.size());
        return retained;
    }

    /**
     * Fraction of {@code candidate}'s shingles that also appear in {@code retained}.
     * Returns 0.0 for an empty candidate so content-less chunks are never treated as covered.
     */
    private double containment(final Set<String> candidate, final Set<String> retained) {
        if (candidate.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        for (final String shingle : candidate) {
            if (retained.contains(shingle)) {
                intersection++;
            }
        }
        return (double) intersection / candidate.size();
    }

    /**
     * Builds the set of word-level n-gram shingles for a chunk. Text is lowercased and split on
     * non-alphanumeric runs so punctuation and whitespace differences do not affect the comparison.
     * Chunks shorter than the shingle size produce a single shingle of all their tokens.
     */
    private Set<String> shingles(final String text) {
        final Set<String> shingles = new HashSet<>();
        if (text == null || text.isBlank()) {
            return shingles;
        }

        final List<String> tokens = new ArrayList<>();
        for (final String token : text.toLowerCase(Locale.ROOT).split(NON_ALPHANUMERIC)) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            return shingles;
        }

        final int size = Math.min(shingleSize, tokens.size());
        for (int i = 0; i + size <= tokens.size(); i++) {
            shingles.add(String.join(" ", tokens.subList(i, i + size)));
        }
        return shingles;
    }
}
