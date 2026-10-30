package uk.gov.moj.cp.orchestrator.eval;

import java.util.List;

/**
 * One query of the golden dataset with everything needed to score its answer. Defaults are
 * already applied: {@code queryPrompt} falls back to the dataset prompt and {@code minFactRecall}
 * to 1.0 (every expected fact required).
 *
 * @param retrieval optional document-level checks on the final chunk set; null when absent
 */
public record EvalItem(String id,
                       String behaviour,
                       List<String> tiers,
                       String query,
                       String queryPrompt,
                       List<MetadataEntry> metadataFilter,
                       List<ExpectedFact> expectedFacts,
                       List<Forbidden> forbidden,
                       RetrievalCheck retrieval,
                       boolean shouldRefuse,
                       double minFactRecall,
                       boolean citationsPerSection) {

    /** An item with a recall threshold below 1.0 expects a long answer (summary, chronology). */
    public boolean isLongAnswer() {
        return minFactRecall < 1.0;
    }

    /**
     * A fact the answer must contain. Several refs are alternatives (the same fact in duplicate
     * copies); {@code accept} lists extra spellings a correct answer may use.
     */
    public record ExpectedFact(List<FactRef> refs, List<String> accept) {
    }

    /** Content the answer must not contain: a manifest fact (from the wrong document) or a literal text. */
    public record Forbidden(FactRef ref, String text) {

        String label() {
            return ref != null ? ref.toString() : text;
        }
    }

    /** Documents (by file name) that must appear in the final chunks, and groups of which at most one may. */
    public record RetrievalCheck(List<String> mustInclude, List<List<String>> atMostOneOf) {
    }
}
