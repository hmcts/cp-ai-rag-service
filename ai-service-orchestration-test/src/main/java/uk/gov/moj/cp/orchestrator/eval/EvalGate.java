package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The PR gate (DD-43901) over the deterministic measures. A run fails the gate when the answer was
 * not generated (error, timeout, failed), fact recall over the gated facts is below the item
 * threshold, forbidden content appears, the refusal is wrong, a retrieval check fails, a chunk is
 * outside the filter, the answer is citation-degraded, or a section is uncited. A check in the
 * known-issues register is reported but not gated. Citation precision, gold-page recall,
 * groundedness and latency stay report-only.
 *
 * <p>The decision per item is 2-of-3: the first run decides when it passes; when it fails, the
 * first three runs decide and at least two must pass. {@link #needsRerun} tells the runner when an
 * item needs more runs to reach a decision.</p>
 */
final class EvalGate {

    static final int ATTEMPTS = 3;
    static final int PASSES_NEEDED = 2;

    private static final String ANSWER_GENERATED = "ANSWER_GENERATED";

    private final KnownIssues knownIssues;

    EvalGate(final KnownIssues knownIssues) {
        this.knownIssues = knownIssues;
    }

    /** What one run did against the gate: gated failures, and the registered known issues it showed. */
    record RunOutcome(int run, List<String> failures, List<String> knownIssuesSeen) {

        boolean passed() {
            return failures.isEmpty();
        }
    }

    /** The gate decision for one item, with the runs it was based on. */
    record ItemVerdict(String itemId, boolean passed, List<RunOutcome> runs) {
    }

    /** The gate decision for the whole eval run, written into the report. */
    record Result(boolean passed, List<ItemVerdict> items, List<KnownIssues.KnownIssue> knownIssues) {

        List<ItemVerdict> failedItems() {
            return items.stream().filter(i -> !i.passed()).toList();
        }
    }

    RunOutcome outcome(final EvalItem item, final ItemRun run) {
        final ItemScore s = run.score();
        final List<String> failures = new ArrayList<>();
        final List<String> known = new ArrayList<>();
        if (!ANSWER_GENERATED.equals(s.status())) {
            failures.add("status: " + s.status() + (run.error() == null ? "" : " (" + run.error() + ")"));
            return new RunOutcome(run.run(), List.copyOf(failures), List.of());
        }

        final List<String> knownFacts = item.expectedFacts().stream().map(f -> f.refs().get(0).factId())
                .filter(id -> knownIssues.isKnown(item.id(), "fact:" + id)).toList();
        final List<String> gatedMissing = s.missingFacts().stream().filter(id -> !knownFacts.contains(id)).toList();
        s.missingFacts().stream().filter(knownFacts::contains).forEach(id -> known.add("fact:" + id));
        final int gatedFacts = item.expectedFacts().size() - knownFacts.size();
        if (gatedFacts > 0 && (double) (gatedFacts - gatedMissing.size()) / gatedFacts < item.minFactRecall()) {
            failures.add("fact-recall: missing " + gatedMissing + " (threshold " + item.minFactRecall() + ")");
        }

        s.forbiddenHits().forEach(label -> check(item, "forbidden:" + label, "forbidden: " + label, failures, known));
        if (!s.refusalCorrect()) {
            check(item, "refusal", "refusal: " + (s.refused() ? "refused an answerable query" : "answered instead of refusing"),
                    failures, known);
        }
        if (Boolean.FALSE.equals(s.retrievalOk())) {
            check(item, "retrieval", "retrieval: documents " + s.retrievedDocuments(), failures, known);
        }
        if (s.chunksOutsideFilter() > 0) {
            check(item, "outside-filter", "outside-filter: " + s.chunksOutsideFilter() + " chunk(s)", failures, known);
        }
        if (s.citationDegraded()) {
            check(item, "citation-degraded", "citation-degraded: " + s.reason(), failures, known);
        }
        if (s.sectionsWithoutCitation() != null && s.sectionsWithoutCitation() > 0) {
            check(item, "sections-uncited", "sections-uncited: " + s.sectionsWithoutCitation() + " of " + s.sections(),
                    failures, known);
        }
        return new RunOutcome(run.run(), List.copyOf(failures), List.copyOf(known));
    }

    /** True when the item's first run failed and fewer than {@link #ATTEMPTS} runs exist. */
    boolean needsRerun(final EvalItem item, final List<ItemRun> runs) {
        final List<ItemRun> ordered = ordered(runs);
        return !ordered.isEmpty() && !outcome(item, ordered.get(0)).passed() && ordered.size() < ATTEMPTS;
    }

    ItemVerdict verdict(final EvalItem item, final List<ItemRun> runs) {
        final List<RunOutcome> outcomes = ordered(runs).stream().map(r -> outcome(item, r)).toList();
        if (outcomes.isEmpty()) {
            return new ItemVerdict(item.id(), false, outcomes);
        }
        if (outcomes.get(0).passed()) {
            return new ItemVerdict(item.id(), true, outcomes);
        }
        final List<RunOutcome> deciding = outcomes.subList(0, Math.min(ATTEMPTS, outcomes.size()));
        return new ItemVerdict(item.id(), deciding.stream().filter(RunOutcome::passed).count() >= PASSES_NEEDED, outcomes);
    }

    Result decide(final List<EvalItem> items, final List<ItemRun> runs) {
        final List<ItemVerdict> verdicts = items.stream()
                .map(i -> verdict(i, runs.stream().filter(r -> r.itemId().equals(i.id())).toList())).toList();
        final Set<KnownIssues.KnownIssue> relevant = new LinkedHashSet<>();
        items.forEach(i -> relevant.addAll(knownIssues.forItem(i.id())));
        return new Result(verdicts.stream().allMatch(ItemVerdict::passed), verdicts, List.copyOf(relevant));
    }

    private void check(final EvalItem item, final String check, final String failure,
                       final List<String> failures, final List<String> known) {
        if (knownIssues.isKnown(item.id(), check)) {
            known.add(check);
        } else {
            failures.add(failure);
        }
    }

    private static List<ItemRun> ordered(final List<ItemRun> runs) {
        return runs.stream().sorted(Comparator.comparingInt(ItemRun::run)).toList();
    }
}
