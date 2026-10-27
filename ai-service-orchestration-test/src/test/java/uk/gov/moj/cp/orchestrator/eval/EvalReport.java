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
 * of every item with its measures, and summaries. Phase 1 is report-only, so nothing here gates;
 * thresholds come later from these reports (OQ-5).
 */
public record EvalReport(String datasetVersion,
                         String generatedAt,
                         Map<String, String> settings,
                         List<DocumentIngestion> ingestion,
                         List<ItemResult> items,
                         Summary summary) {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** M1: one corpus document's ingestion outcome. */
    public record DocumentIngestion(String file, String documentId, String status, double seconds) {
    }

    /** One run of one item; {@code groundedness} is the scorer's score (M11), null if none was recorded. */
    public record ItemRun(String itemId, int run, String transactionId, Double groundedness, ItemScore score) {
    }

    public record ItemResult(String id, String behaviour, List<ItemRun> runs, ItemSummary summary) {
    }

    /** Counts and means over an item's runs, so run-to-run variance is visible. */
    public record ItemSummary(int runs,
                              int cleanRuns,
                              int factRecallPasses,
                              int forbiddenHitRuns,
                              int refusalErrors,
                              int citationDegradedRuns,
                              Double meanFactRecall,
                              Double meanCitationPrecision,
                              Double meanGroundedness) {
    }

    public record Summary(int items,
                          int runs,
                          int cleanRuns,
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
                items.add(new ItemResult(item.id(), item.behaviour(), itemRuns, summarise(itemRuns)));
            }
        }
        final List<ItemScore> scores = runs.stream().map(ItemRun::score).toList();
        final Summary summary = new Summary(
                items.size(),
                runs.size(),
                count(scores, EvalReport::isClean),
                ingestion.stream().allMatch(d -> "INGESTION_SUCCESS".equals(d.status())),
                mean(scores.stream().map(ItemScore::factRecall).toList()),
                mean(scores.stream().map(ItemScore::goldPageRecall).toList()),
                scores.isEmpty() ? null : round((double) count(scores, ItemScore::citationDegraded) / scores.size()),
                mean(runs.stream().map(ItemRun::groundedness).toList()),
                percentile(scores, ItemScore::elapsedSeconds, 0.50),
                percentile(scores, ItemScore::elapsedSeconds, 0.95));
        return new EvalReport(dataset.version(), Instant.now().toString(), Map.copyOf(settings), List.copyOf(ingestion),
                List.copyOf(items), summary);
    }

    /**
     * Every applicable measure passes: fact recall meets the item threshold, no forbidden content,
     * refusal correct, retrieval checks hold, nothing outside the filter, not citation-degraded and
     * every section cited. Citation precision is left out until it has a threshold (OQ-5).
     */
    static boolean isClean(final ItemScore s) {
        return !Boolean.FALSE.equals(s.factRecallPass())
                && s.forbiddenHits().isEmpty()
                && s.refusalCorrect()
                && !Boolean.FALSE.equals(s.retrievalOk())
                && s.chunksOutsideFilter() == 0
                && !s.citationDegraded()
                && (s.sectionsWithoutCitation() == null || s.sectionsWithoutCitation() == 0);
    }

    void write(final Path file) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), this);
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot write eval report " + file, e);
        }
    }

    private static ItemSummary summarise(final List<ItemRun> runs) {
        final List<ItemScore> scores = runs.stream().map(ItemRun::score).toList();
        return new ItemSummary(
                scores.size(),
                count(scores, EvalReport::isClean),
                count(scores, s -> Boolean.TRUE.equals(s.factRecallPass())),
                count(scores, s -> !s.forbiddenHits().isEmpty()),
                count(scores, s -> !s.refusalCorrect()),
                count(scores, ItemScore::citationDegraded),
                mean(scores.stream().map(ItemScore::factRecall).toList()),
                mean(scores.stream().map(ItemScore::citationPrecision).toList()),
                mean(runs.stream().map(ItemRun::groundedness).toList()));
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
