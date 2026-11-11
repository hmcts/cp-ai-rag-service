package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static uk.gov.moj.cp.orchestrator.eval.AnswerCases.DATASET;

import uk.gov.moj.cp.orchestrator.eval.AnswerCases.MissingRetrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Scores the hand-written answers in {@code reference-answers.json} through the eval's scorer and
 * gate, with no known issues applied: a correct answer must pass every gated check, and a wrong
 * one must fail exactly the checks listed. This proves each item can be passed and that the
 * checks accept the usual ways of writing a correct answer, without calling the service.
 */
class ReferenceAnswersTest {

    private static final String FILE = "reference-answers.json";

    @Test
    @DisplayName("Every dataset item has at least one passing and one failing reference answer, and every entry names an item")
    void coversEveryItem() {
        final JsonNode references = AnswerCases.items(FILE);
        final Set<String> ids = DATASET.items().stream().map(EvalItem::id).collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> referenced = new LinkedHashSet<>();
        references.fieldNames().forEachRemaining(referenced::add);

        assertEquals(ids, referenced);
        assertAll(ids.stream().map(id -> () -> {
            assertFalse(references.path(id).path("pass").isEmpty(), id + " has no passing reference answer");
            assertFalse(references.path(id).path("fail").isEmpty(), id + " has no failing reference answer");
        }));
    }

    @TestFactory
    @DisplayName("Each correct reference answer passes the gate")
    Stream<DynamicTest> correctAnswersPass() {
        return AnswerCases.read(FILE, "pass").stream().map(c -> dynamicTest(c.itemId() + ": " + c.label(),
                () -> assertEquals(List.of(), AnswerCases.failedChecks(c, MissingRetrieval.GOLD_PAGES), c.label())));
    }

    @TestFactory
    @DisplayName("Each wrong reference answer fails exactly the listed checks")
    Stream<DynamicTest> wrongAnswersFail() {
        return AnswerCases.read(FILE, "fail").stream().map(c -> dynamicTest(c.itemId() + ": " + c.label(),
                () -> assertEquals(c.failures(), AnswerCases.failedChecks(c, MissingRetrieval.GOLD_PAGES), c.label())));
    }
}
