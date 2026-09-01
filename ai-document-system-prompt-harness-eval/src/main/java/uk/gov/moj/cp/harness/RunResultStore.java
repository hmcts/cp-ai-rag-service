package uk.gov.moj.cp.harness;

import static uk.gov.moj.cp.harness.HarnessEnv.env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists a harness run's full per-cell results — raw and citation-processed answers plus the
 * computed compliance metrics — to a JSON file under {@code harness-results/} (git-ignored:
 * answers quote case-document content). A persisted run is a durable baseline: later runs
 * (e.g. a locally hosted open-source model over the same retrieval snapshot) can be compared
 * against it answer-for-answer without re-running the baseline models.
 *
 * <p>The row key mirrors the in-run identity: (queryLabel, promptLabel, llmLabel, iteration).
 * The {@code meta} header records what produced the run — models, prompt, query file, retrieval
 * source (live or snapshot path) and generation knobs — so a baseline is interpretable on its
 * own. Output directory override: {@code HARNESS_RESULTS_DIR}.
 */
final class RunResultStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunResultStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_RESULTS_DIR = "harness-results";
    private static final String MODULE_DIR_NAME = "ai-document-system-prompt-harness-eval";

    private RunResultStore() {
    }

    static Path persist(final List<TestHarness.RunResult> results,
                        final List<TestHarness.SystemPromptConfig> prompts,
                        final List<TestHarness.LlmConfig> llms,
                        final String queryFile,
                        final String snapshotFile) throws Exception {
        final Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        meta.put("queryFile", queryFile);
        meta.put("retrievalSource", snapshotFile.isEmpty() ? "live" : snapshotFile);
        meta.put("prompts", prompts.stream().map(TestHarness.SystemPromptConfig::label).toList());
        meta.put("llms", llms.stream()
                .map(lc -> (lc.provider().isEmpty() ? "" : lc.provider() + ":") + lc.deployment()).toList());
        meta.put("repetitions", env("HARNESS_REPETITIONS", "3"));
        meta.put("maxCompletionTokens", env("LLM_MODEL_RESPONSE_MAX_TOKENS", ""));
        meta.put("reasoningEffort", env("LLM_REASONING_EFFORT", ""));
        meta.put("citationGuardMode", env("CITATION_GUARD_MODE", ""));

        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final TestHarness.RunResult r : results) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("iteration", r.iteration());
            row.put("queryLabel", r.queryLabel());
            row.put("promptLabel", r.promptLabel());
            row.put("llmLabel", r.llmLabel());
            row.put("durationMs", r.durationMs());
            row.put("status", r.response() != null ? String.valueOf(r.response().status()) : null);
            row.put("error", r.error());
            if (r.response() != null) {
                row.put("rawResponse", r.response().rawLlmResponse());
                row.put("formattedResponse", r.response().formattedLlmResponse());
                row.put("metrics", metricsOf(TestHarness.computeCompliance(r.response())));
            }
            rows.add(row);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("results", rows);

        final Path dir = resolveResultsDir();
        Files.createDirectories(dir);
        final Path file = dir.resolve("harness-run-"
                + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        LOGGER.info("[results] persisted {} result rows to {}", rows.size(), file.toAbsolutePath());
        return file;
    }

    /** Flattens the compliance record to the scalar metrics the reports print. */
    private static Map<String, Object> metricsOf(final CitationMetrics.Compliance c) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonBlockPresent", c.jsonBlockPresent());
        m.put("inlineMatchesJson", c.inlineSubsetOfJson());
        m.put("processorSubstituted", c.processorSubstituted());
        m.put("proseChars", c.proseChars());
        m.put("proseWords", c.proseWords());
        m.put("distinctCitations", c.jsonIds().size());
        m.put("citedPages", c.citedPages());
        m.put("sameDocStackedRuns", c.sameDocStackedRuns());
        m.put("renderedCitations", c.renderedCitations());
        m.put("strippedMarkers", c.strippedMarkers());
        m.put("uncitedSubstantive", c.uncitedSubstantive());
        return m;
    }

    /** Module-local results dir regardless of whether the JVM started at the repo or module root. */
    private static Path resolveResultsDir() {
        final String override = env("HARNESS_RESULTS_DIR", "");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        return Files.isDirectory(Paths.get(MODULE_DIR_NAME))
                ? Paths.get(MODULE_DIR_NAME, DEFAULT_RESULTS_DIR)
                : Paths.get(DEFAULT_RESULTS_DIR);
    }
}
