package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static uk.gov.moj.cp.orchestrator.eval.AnswerCases.DATASET;

import uk.gov.moj.cp.orchestrator.eval.AnswerCases.MissingRetrieval;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Scores the answers the live service actually gave ({@code live-answers.json}) through the eval's
 * scorer and gate, with no known issues applied, under the same rules as the reference answers: a
 * 'pass' answer must pass every gated check, and a 'fail' answer must fail exactly the checks listed.
 * A change to the scorer or the dataset that alters a verdict on real model output fails here; if the
 * change is intended, move the answer between 'pass' and 'fail' (or edit its checks) and say why in
 * its label. Answers from runs that did not record retrieval are scored without their retrieval check.
 */
class LiveAnswersTest {

    private static final String FILE = "live-answers.json";

    @Test
    @DisplayName("Every entry names a dataset item")
    void namesDatasetItems() {
        AnswerCases.items(FILE).fieldNames().forEachRemaining(id ->
                assertTrue(DATASET.items().stream().anyMatch(i -> i.id().equals(id)), id + " is not a dataset item"));
    }

    @TestFactory
    @DisplayName("Each live answer recorded as passing still passes the gate")
    Stream<DynamicTest> passingAnswersPass() {
        return AnswerCases.read(FILE, "pass").stream().map(c -> dynamicTest(c.itemId() + ": " + c.label(),
                () -> assertEquals(List.of(), AnswerCases.failedChecks(c, MissingRetrieval.NOT_RECORDED), c.label())));
    }

    @TestFactory
    @DisplayName("Each live answer recorded as failing still fails exactly the listed checks")
    Stream<DynamicTest> failingAnswersFail() {
        return AnswerCases.read(FILE, "fail").stream().map(c -> dynamicTest(c.itemId() + ": " + c.label(),
                () -> assertEquals(c.failures(), AnswerCases.failedChecks(c, MissingRetrieval.NOT_RECORDED), c.label())));
    }
}
