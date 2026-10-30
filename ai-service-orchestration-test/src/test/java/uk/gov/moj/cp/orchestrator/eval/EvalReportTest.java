package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.EvalReport.DocumentIngestion;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalReportTest {

    private static final EvalDataset DATASET = EvalDataset.loadDefault();

    private static ItemScore score(final String id, final Double recall, final boolean recallPass, final List<String> forbidden,
                                   final boolean degraded, final Double precision, final double seconds) {
        return score(id, "ANSWER_GENERATED", recall, recallPass, forbidden, degraded, precision, seconds);
    }

    private static ItemScore score(final String id, final String status, final Double recall, final boolean recallPass,
                                   final List<String> forbidden, final boolean degraded, final Double precision,
                                   final double seconds) {
        return new ItemScore(id, status, seconds, recall, recallPass, List.of(), forbidden, false, true,
                1.0, null, List.of(), 0, 1, precision, true, null, null, degraded, null, 3, 100);
    }

    private static EvalReport report(final List<ItemRun> runs) {
        return EvalReport.build(DATASET, Map.of("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", "gpt-x"),
                List.of(new DocumentIngestion("a.pdf", "id-a", "INGESTION_SUCCESS", 40.0)), runs);
    }

    @Test
    @DisplayName("A run is clean only when every applicable measure passes")
    void decidesCleanRuns() {
        assertTrue(EvalReport.isClean(score("plain-001", 1.0, true, List.of(), false, 1.0, 5)));
        assertFalse(EvalReport.isClean(score("plain-001", 0.5, false, List.of(), false, 1.0, 5)));
        assertFalse(EvalReport.isClean(score("plain-001", 1.0, true, List.of("x"), false, 1.0, 5)));
        assertFalse(EvalReport.isClean(score("plain-001", 1.0, true, List.of(), true, 1.0, 5)));
    }

    @Test
    @DisplayName("A run without a generated answer is never clean, even when no measure applies")
    void runWithoutAnswerIsNotClean() {
        assertFalse(EvalReport.isClean(score("refusal-001", "ERROR", null, true, List.of(), false, null, 5)));
        assertFalse(EvalReport.isClean(score("refusal-001", "TIMEOUT", null, true, List.of(), false, null, 5)));
    }

    @Test
    @DisplayName("A failed run is kept in the report and counted as an error, alongside the other runs")
    void countsErrorRuns() {
        final EvalReport report = report(List.of(
                new ItemRun("plain-001", 1, "t1", null, score("plain-001", 1.0, true, List.of(), false, 1.0, 10), "answer", null),
                new ItemRun("plain-001", 2, null, null, score("plain-001", "ERROR", 0.0, false, List.of(), false, null, 1),
                        null, "java.lang.AssertionError: HTTP 429")));

        assertEquals(1, report.items().get(0).summary().errorRuns());
        assertEquals(1, report.items().get(0).summary().cleanRuns());
        assertEquals(2, report.summary().runs());
        assertEquals(1, report.summary().errorRuns());
    }

    @Test
    @DisplayName("A document that did not upload or ingest fails M1, and the report still builds without items")
    void reportsIngestionFailureWithoutItems() {
        final EvalReport report = EvalReport.build(DATASET, Map.of(), List.of(
                new DocumentIngestion("a.pdf", "id-a", "INGESTION_SUCCESS", 40.0),
                new DocumentIngestion("b.pdf", null, "UPLOAD_FAILED", 1.0)), List.of());

        assertFalse(report.summary().allDocumentsIngested());
        assertEquals(0, report.summary().runs());
        assertTrue(report.items().isEmpty());
        assertNull(report.summary().latencyP50Seconds());
    }

    @Test
    @DisplayName("Runs are grouped per item in dataset order, with counts and means over the runs")
    void summarisesItems() {
        final EvalReport report = report(List.of(
                new ItemRun("ocr-001", 1, "t1", 4.0, score("ocr-001", 1.0, true, List.of(), false, 1.0, 10), "answer", null),
                new ItemRun("plain-001", 1, "t2", null, score("plain-001", 1.0, true, List.of(), false, 1.0, 20), "answer", null),
                new ItemRun("plain-001", 2, "t3", 5.0, score("plain-001", 0.5, false, List.of(), true, 0.5, 30), "answer", null)));

        assertEquals(List.of("plain-001", "ocr-001"), report.items().stream().map(EvalReport.ItemResult::id).toList());
        final EvalReport.ItemSummary plain = report.items().get(0).summary();
        assertEquals(2, plain.runs());
        assertEquals(1, plain.cleanRuns());
        assertEquals(1, plain.factRecallPasses());
        assertEquals(1, plain.citationDegradedRuns());
        assertEquals(0.75, plain.meanFactRecall());
        assertEquals(0.75, plain.meanCitationPrecision());
        assertEquals(5.0, plain.meanGroundedness());
    }

    @Test
    @DisplayName("The overall summary reports means, the degraded rate and latency percentiles")
    void summarisesRun() {
        final EvalReport report = report(List.of(
                new ItemRun("ocr-001", 1, "t1", null, score("ocr-001", 1.0, true, List.of(), false, null, 10), "answer", null),
                new ItemRun("plain-001", 1, "t2", null, score("plain-001", 0.5, false, List.of(), true, 1.0, 20), "answer", null),
                new ItemRun("refusal-001", 1, "t3", null, score("refusal-001", null, true, List.of(), false, null, 30), "answer", null)));

        final EvalReport.Summary summary = report.summary();
        assertEquals(3, summary.runs());
        assertEquals(2, summary.cleanRuns());
        assertEquals(0.75, summary.meanFactRecall());
        assertEquals(0.33, summary.citationDegradedRate());
        assertEquals(20.0, summary.latencyP50Seconds());
        assertEquals(30.0, summary.latencyP95Seconds());
        assertNull(summary.meanGroundedness());
        assertTrue(summary.allDocumentsIngested());
    }

    @Test
    @DisplayName("The report is written as JSON with settings, ingestion and items")
    void writesJson(@TempDir final Path dir) throws Exception {
        final Path file = dir.resolve("eval/eval-report.json");
        report(List.of(new ItemRun("plain-001", 1, "t1", null, score("plain-001", 1.0, true, List.of(), false, 1.0, 5), "answer", null))).write(file);

        final JsonNode json = new ObjectMapper().readTree(Files.readString(file));
        assertEquals("eval-v1", json.path("datasetVersion").asText());
        assertEquals("gpt-x", json.path("settings").path("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME").asText());
        assertEquals("INGESTION_SUCCESS", json.path("ingestion").get(0).path("status").asText());
        assertEquals("plain-001", json.path("items").get(0).path("runs").get(0).path("score").path("id").asText());
    }
}
