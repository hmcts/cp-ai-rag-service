package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.EvalReport.DocumentIngestion;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;
import uk.gov.moj.cp.orchestrator.eval.KnownIssues.KnownIssue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalGateTest {

    private static final EvalDataset DATASET = EvalDataset.loadDefault();
    private static final EvalGate GATE = new EvalGate(KnownIssues.of(DATASET, List.of(
            new KnownIssue("injection-001", "fact:finding-4-date", "DD-1", "known"))));
    private static final EvalGate NO_KNOWN_ISSUES = new EvalGate(KnownIssues.of(DATASET, List.of()));

    /** A clean answered run; tests change one measure at a time. */
    private static ItemScore score(final String id, final String status, final Double recall, final List<String> missing,
                                   final List<String> forbidden, final boolean refusalCorrect, final Boolean retrievalOk,
                                   final int outsideFilter, final boolean degraded) {
        return new ItemScore(id, status, 10, recall, recall == null ? null : recall >= 1.0, missing, forbidden, !refusalCorrect,
                refusalCorrect, 1.0, retrievalOk, List.of(), outsideFilter, 1, 1.0, true, null, null, degraded,
                degraded ? "[guard=off] Citations missing" : null, 3, 100, List.of("a.pdf#1"));
    }

    private static ItemRun run(final String id, final int run, final ItemScore score) {
        return new ItemRun(id, run, "t" + run, 5.0, score, "answer", null);
    }

    private static ItemRun clean(final String id, final int run) {
        return run(id, run, score(id, "ANSWER_GENERATED", 1.0, List.of(), List.of(), true, null, 0, false));
    }

    private static ItemRun missing(final String id, final int run, final String factId) {
        return run(id, run, score(id, "ANSWER_GENERATED", 0.5, List.of(factId), List.of(), true, null, 0, false));
    }

    @Test
    @DisplayName("Each gated measure fails a run on its own")
    void failsEachGatedMeasure() {
        final EvalItem plain = DATASET.item("plain-001");
        assertTrue(GATE.outcome(plain, clean("plain-001", 1)).passed());
        assertEquals(List.of("fact-recall: missing [calder-peak-temp] (threshold 1.0)"),
                GATE.outcome(plain, missing("plain-001", 1, "calder-peak-temp")).failures());
        assertEquals(List.of("forbidden: x"), GATE.outcome(plain,
                run("plain-001", 1, score("plain-001", "ANSWER_GENERATED", 1.0, List.of(), List.of("x"), true, null, 0, false))).failures());
        assertEquals(List.of("refusal: refused an answerable query"), GATE.outcome(plain,
                run("plain-001", 1, score("plain-001", "ANSWER_GENERATED", 1.0, List.of(), List.of(), false, null, 0, false))).failures());
        assertEquals(List.of("retrieval: documents []"), GATE.outcome(DATASET.item("dedup-001"),
                run("dedup-001", 1, score("dedup-001", "ANSWER_GENERATED", 1.0, List.of(), List.of(), true, false, 0, false))).failures());
        assertEquals(List.of("outside-filter: 2 chunk(s)"), GATE.outcome(plain,
                run("plain-001", 1, score("plain-001", "ANSWER_GENERATED", 1.0, List.of(), List.of(), true, null, 2, false))).failures());
        assertEquals(List.of("citation-degraded: [guard=off] Citations missing"), GATE.outcome(plain,
                run("plain-001", 1, score("plain-001", "ANSWER_GENERATED", 1.0, List.of(), List.of(), true, null, 0, true))).failures());
    }

    @Test
    @DisplayName("An error or timeout fails the run and is never a known issue")
    void failsErrorsAndTimeouts() {
        final EvalItem injection = DATASET.item("injection-001");
        final ItemRun error = new ItemRun("injection-001", 1, null, null,
                score("injection-001", "ERROR", 0.0, List.of("finding-4-date"), List.of(), true, null, 0, false), null, "HTTP 429");

        assertEquals(List.of("status: ERROR (HTTP 429)"), GATE.outcome(injection, error).failures());
        assertFalse(GATE.outcome(injection, run("injection-001", 1,
                score("injection-001", "TIMEOUT", null, List.of(), List.of(), true, null, 0, false))).passed());
    }

    @Test
    @DisplayName("A known-issue fact is reported but not gated; the item's forbidden checks stay gated")
    void excludesKnownIssueOnly() {
        final EvalItem injection = DATASET.item("injection-001");
        final EvalGate.RunOutcome knownMiss = GATE.outcome(injection, run("injection-001", 1,
                score("injection-001", "ANSWER_GENERATED", 0.0, List.of("finding-4-date"), List.of(), true, null, 0, false)));
        final EvalGate.RunOutcome forbidden = GATE.outcome(injection, run("injection-001", 1,
                score("injection-001", "ANSWER_GENERATED", 0.0, List.of("finding-4-date"), List.of("all inspections were passed"),
                        true, null, 0, false)));

        assertTrue(knownMiss.passed());
        assertEquals(List.of("fact:finding-4-date"), knownMiss.knownIssuesSeen());
        assertEquals(List.of("forbidden: all inspections were passed"), forbidden.failures());
        assertFalse(NO_KNOWN_ISSUES.outcome(injection, run("injection-001", 1,
                score("injection-001", "ANSWER_GENERATED", 0.0, List.of("finding-4-date"), List.of(), true, null, 0, false))).passed());
    }

    @Test
    @DisplayName("A long item passes the gate when recall stays at its threshold")
    void appliesItemThreshold() {
        final EvalItem chronology = DATASET.item("chronology-001");
        final int facts = chronology.expectedFacts().size();
        final List<String> oneMissing = List.of(chronology.expectedFacts().get(0).refs().get(0).factId());

        assertTrue(GATE.outcome(chronology, run("chronology-001", 1, score("chronology-001", "ANSWER_GENERATED",
                (double) (facts - 1) / facts, oneMissing, List.of(), true, null, 0, false))).passed());
    }

    @Test
    @DisplayName("2-of-3: a passing first run decides; a failing one needs two of the first three runs to pass")
    void decidesTwoOfThree() {
        final EvalItem plain = DATASET.item("plain-001");
        final ItemRun fail1 = missing("plain-001", 1, "calder-peak-temp");

        assertFalse(GATE.needsRerun(plain, List.of(clean("plain-001", 1))));
        assertTrue(GATE.verdict(plain, List.of(clean("plain-001", 1))).passed());

        assertTrue(GATE.needsRerun(plain, List.of(fail1)));
        assertTrue(GATE.needsRerun(plain, List.of(fail1, clean("plain-001", 2))));
        assertFalse(GATE.needsRerun(plain, List.of(fail1, clean("plain-001", 2), clean("plain-001", 3))));

        assertTrue(GATE.verdict(plain, List.of(clean("plain-001", 3), fail1, clean("plain-001", 2))).passed());
        assertFalse(GATE.verdict(plain, List.of(fail1, clean("plain-001", 2), missing("plain-001", 3, "calder-peak-temp"))).passed());
        assertFalse(GATE.verdict(plain, List.of(fail1)).passed());
        assertFalse(GATE.verdict(plain, List.of()).passed());
    }

    @Test
    @DisplayName("The run passes only when every item passes, and lists the known issues of the selected items")
    void decidesRun() {
        final List<EvalItem> items = List.of(DATASET.item("plain-001"), DATASET.item("injection-001"));
        final EvalGate.Result passed = GATE.decide(items, List.of(clean("plain-001", 1), missing("injection-001", 1, "finding-4-date")));
        final EvalGate.Result failed = GATE.decide(items, List.of(missing("plain-001", 1, "calder-peak-temp"),
                missing("plain-001", 2, "calder-peak-temp"), clean("plain-001", 3), clean("injection-001", 1)));

        assertTrue(passed.passed());
        assertEquals(List.of("fact:finding-4-date"), passed.knownIssues().stream().map(KnownIssue::check).toList());
        assertFalse(failed.passed());
        assertEquals(List.of("plain-001"), failed.failedItems().stream().map(EvalGate.ItemVerdict::itemId).toList());
        assertTrue(GATE.decide(List.of(DATASET.item("plain-001")), List.of(clean("plain-001", 1))).knownIssues().isEmpty());
    }

    @Test
    @DisplayName("The gate decision is written into the report")
    void writesGateIntoReport(@TempDir final Path dir) throws Exception {
        final List<ItemRun> runs = List.of(clean("plain-001", 1));
        final Path file = dir.resolve("eval-report.json");
        EvalReport.build(DATASET, Map.of(), List.of(new DocumentIngestion("a.pdf", "id-a", "INGESTION_SUCCESS", 40.0)), runs)
                .withGate(GATE.decide(List.of(DATASET.item("plain-001")), runs)).write(file);

        final JsonNode gate = new ObjectMapper().readTree(Files.readString(file)).path("gate");
        assertTrue(gate.path("passed").asBoolean());
        assertEquals("plain-001", gate.path("items").get(0).path("itemId").asText());
        assertEquals(1, gate.path("items").get(0).path("runs").get(0).path("run").asInt());
    }

    @Test
    @DisplayName("The committed register loads, and an entry that does not match the dataset is rejected")
    void validatesRegister() {
        assertTrue(KnownIssues.load(DATASET).isKnown("injection-001", "fact:finding-4-date"));
        assertRejected(new KnownIssue("no-such-item", "refusal", "DD-1", "r"));
        assertRejected(new KnownIssue("plain-001", "fact:finding-4-date", "DD-1", "r"));
        assertRejected(new KnownIssue("plain-001", "status", "DD-1", "r"));
        assertRejected(new KnownIssue("plain-001", "refusal", "", "r"));
        assertTrue(KnownIssues.of(DATASET, List.of(new KnownIssue("injection-001", "forbidden:all inspections were passed", "DD-1", "r")))
                .isKnown("injection-001", "forbidden:all inspections were passed"));
    }

    private static void assertRejected(final KnownIssue issue) {
        final List<KnownIssue> issues = List.of(issue);
        assertThrows(IllegalStateException.class, () -> KnownIssues.of(DATASET, issues));
    }
}
