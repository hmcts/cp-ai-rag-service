package uk.gov.moj.cp.orchestrator.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The output of one eval run (design D2): the settings it ran with, how ingestion went, every run
 * of every item with its measures, summaries and, when the gate is on, the gate decision
 * ({@link EvalGate}, null when the run is report-only).
 */
public record EvalReport(String datasetVersion,
                         String generatedAt,
                         Map<String, String> settings,
                         List<DocumentIngestion> ingestion,
                         List<ItemResult> items,
                         Summary summary,
                         EvalGate.Result gate) {


    private static final EvalGate NO_KNOWN_ISSUES = new EvalGate(KnownIssues.none());

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** M1: one corpus document's ingestion outcome; {@code seconds} runs from its upload to its final status. */
    public record DocumentIngestion(String file, String documentId, String status, double seconds) {
    }

    /**
     * One run of one item; {@code groundedness} is the scorer's score (M11), null if none was
     * recorded. The answer is kept as rendered, so a miss can be diagnosed from the report alone.
     * {@code error} is set when the run itself failed (a request, contract or scoring error), so one
     * failed query is recorded instead of losing the whole report.
     */
    public record ItemRun(String itemId, int run, String transactionId, Double groundedness, ItemScore score, String answer,
                          String error) {
    }

    public record ItemResult(String id, String behaviour, List<ItemRun> runs, ItemSummary summary) {
    }

    /**
     * Counts and means over an item's runs, so run-to-run variance is visible. {@code errorRuns} counts runs that
     * produced no answer (error, timeout, failed); the quality counts and means are over the runs that did.
     */
    public record ItemSummary(int runs,
                              int cleanRuns,
                              int factRecallPasses,
                              int forbiddenHitRuns,
                              int refusalErrors,
                              int citationDegradedRuns,
                              int errorRuns,
                              Double meanFactRecall,
                              Double meanCitationPrecision,
                              Double meanGroundedness) {
    }

    /** The same over every run; quality means and the degraded rate are over the runs that produced an answer. */
    public record Summary(int items,
                          int runs,
                          int cleanRuns,
                          int errorRuns,
                          boolean allDocumentsIngested,
                          Double meanFactRecall,
                          Double meanGoldPageRecall,
                          Double citationDegradedRate,
                          Double meanGroundedness,
                          Double latencyP50Seconds,
                          Double latencyP95Seconds) {
    }

    static EvalReport build(final EvalDataset dataset, final Map<String, String> settings,
                            final List<DocumentIngestion> ingestion, final List<ItemRun> runs) {
        final List<ItemResult> items = new ArrayList<>();
        for (final EvalItem item : dataset.items()) {
            final List<ItemRun> itemRuns = runs.stream().filter(r -> r.itemId().equals(item.id())).toList();
            if (!itemRuns.isEmpty()) {
                items.add(new ItemResult(item.id(), item.behaviour(), itemRuns, summarise(item, itemRuns)));
            }
        }
        final List<ItemScore> scores = runs.stream().map(ItemRun::score).toList();
        final List<ItemScore> answered = answered(scores);
        final Summary summary = new Summary(
                items.size(),
                runs.size(),
                (int) runs.stream().filter(r -> isClean(dataset.item(r.itemId()), r)).count(),
                scores.size() - answered.size(),
                ingestion.stream().allMatch(d -> "INGESTION_SUCCESS".equals(d.status())),
                mean(answered.stream().map(ItemScore::factRecall).toList()),
                mean(answered.stream().map(ItemScore::goldPageRecall).toList()),
                answered.isEmpty() ? null : round((double) count(answered, ItemScore::citationDegraded) / answered.size()),
                mean(runs.stream().map(ItemRun::groundedness).toList()),
                percentile(scores, ItemScore::elapsedSeconds, 0.50),
                percentile(scores, ItemScore::elapsedSeconds, 0.95));
        return new EvalReport(dataset.version(), Instant.now().toString(), Map.copyOf(settings), List.copyOf(ingestion),
                List.copyOf(items), summary, null);
    }

    EvalReport withGate(final EvalGate.Result result) {
        return new EvalReport(datasetVersion, generatedAt, settings, ingestion, items, summary, result);
    }

    /**
     * The run passes every gated check with no known issues applied: the gate's own outcome, so "clean"
     * and "passed" cannot drift apart. Report-only measures do not affect it.
     */
    static boolean isClean(final EvalItem item, final ItemRun run) {
        return NO_KNOWN_ISSUES.outcome(item, run).passed();
    }

    void write(final Path file) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), this);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot write eval report " + file, e);
        }
    }

    private static ItemSummary summarise(final EvalItem item, final List<ItemRun> runs) {
        final List<ItemScore> scores = runs.stream().map(ItemRun::score).toList();
        final List<ItemScore> answered = answered(scores);
        return new ItemSummary(
                scores.size(),
                (int) runs.stream().filter(r -> isClean(item, r)).count(),
                count(answered, s -> Boolean.TRUE.equals(s.factRecallPass())),
                count(answered, s -> !s.forbiddenHits().isEmpty()),
                count(answered, s -> !s.refusalCorrect()),
                count(answered, ItemScore::citationDegraded),
                scores.size() - answered.size(),
                mean(answered.stream().map(ItemScore::factRecall).toList()),
                mean(answered.stream().map(ItemScore::citationPrecision).toList()),
                mean(runs.stream().map(ItemRun::groundedness).toList()));
    }

    /** The runs that produced an answer; an error or timeout says nothing about answer quality. */
    private static List<ItemScore> answered(final List<ItemScore> scores) {
        return scores.stream().filter(s -> AnswerResult.ANSWER_GENERATED.equals(s.status())).toList();
    }

    private static int count(final List<ItemScore> scores, final Predicate<ItemScore> predicate) {
        return (int) scores.stream().filter(predicate).count();
    }

    private static Double mean(final List<Double> values) {
        final OptionalDouble mean = values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        return mean.isPresent() ? round(mean.getAsDouble()) : null;
    }

    /** Nearest-rank percentile. */
    private static Double percentile(final List<ItemScore> scores, final ToDoubleFunction<ItemScore> value, final double p) {
        if (scores.isEmpty()) {
            return null;
        }
        final double[] sorted = scores.stream().mapToDouble(value).sorted().toArray();
        return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static double round(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
