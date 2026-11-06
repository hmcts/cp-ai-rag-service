package uk.gov.moj.cp.orchestrator.eval;

import java.util.List;
import java.util.regex.Pattern;

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
     * copies); {@code accept} lists extra spellings of the value a correct answer may use, and
     * {@code keywords} accepts a sentence that has one word from every group (a reworded fact).
     *
     * @param near optional: the fact counts only where it is stated next to this anchor; null when absent
     */
    public record ExpectedFact(List<FactRef> refs, List<String> accept, List<List<String>> keywords, Near near) {
    }

    /**
     * Binds a fact to the entry it belongs to: the value and the anchor (a manifest fact, such as the
     * entry's date, or one of several texts, such as "most recent") must be in the same sentence or
     * line with no rival between them. Rivals are the listed facts plus the anchors of the item's other
     * facts, so in a list of dated entries each value has to sit with its own date.
     */
    public record Near(FactRef ref, List<String> text, List<FactRef> rivals) {
    }

    /**
     * Content the answer must not contain: a manifest fact (from the wrong document), or a claim
     * matched by patterns over the normalised answer. A claim the answer quotes or rejects does not count.
     */
    public record Forbidden(FactRef ref, String claim, List<Pattern> patterns) {

        String label() {
            return ref != null ? ref.toString() : claim;
        }
    }

    /** Documents (by file name) that must appear in the final chunks, and groups of which at most one may. */
    public record RetrievalCheck(List<String> mustInclude, List<List<String>> atMostOneOf) {
    }
}
