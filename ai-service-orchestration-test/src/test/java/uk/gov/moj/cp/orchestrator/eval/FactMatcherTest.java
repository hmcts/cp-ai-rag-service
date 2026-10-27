package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.CorpusFact.FactType;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactMatcherTest {

    private static final CorpusFact DATE = new CorpusFact(FactType.DATE, "2025-04-07", List.of(3));
    private static final CorpusFact MONEY = new CorpusFact(FactType.MONEY, "4149.0", List.of(5));
    private static final CorpusFact TEXT = new CorpusFact(FactType.TEXT, "TL-55821", List.of(5));

    @Test
    @DisplayName("A date fact matches every common written form")
    void matchesDateForms() {
        for (final String answer : List.of("07/04/2025", "7/4/2025", "7.4.25", "07-04-2025", "7 April 2025", "7th April 2025",
                "7th of April 2025", "7 Apr 2025", "April 7, 2025", "April 7,2025", "Apr 7th 2025", "2025-04-07", "on **7 April 2025**")) {
            assertTrue(FactMatcher.found(answer, DATE, List.of()), answer);
        }
    }

    @Test
    @DisplayName("A different date does not match")
    void rejectsOtherDates() {
        for (final String answer : List.of("17/04/2025", "07/04/2024", "7 May 2025", "27 April 2025", "2025-04-17")) {
            assertFalse(FactMatcher.found(answer, DATE, List.of()), answer);
        }
    }

    @Test
    @DisplayName("A money fact matches with or without separators, including the spaced form Document Intelligence produces")
    void matchesMoneyForms() {
        for (final String answer : List.of("£4,149", "£4149", "£ 4,149", "£4,149.00", "£4, 149")) {
            assertTrue(FactMatcher.found("Total losses were " + answer + ".", MONEY, List.of()), answer);
        }
    }

    @Test
    @DisplayName("A money fact does not match a different amount or a bare number")
    void rejectsOtherAmounts() {
        for (final String answer : List.of("£41,490", "£4,1490", "£14,149", "4,149 items")) {
            assertFalse(FactMatcher.found(answer, MONEY, List.of()), answer);
        }
    }

    @Test
    @DisplayName("A text fact matches case-insensitively, and an accepted spelling counts as a match")
    void matchesTextAndAcceptedSpellings() {
        assertTrue(FactMatcher.found("the serial is tl-55821", TEXT, List.of()));
        assertFalse(FactMatcher.found("the serial is TL-5582", TEXT, List.of()));

        final CorpusFact outcome = new CorpusFact(FactType.TEXT, "PENALTY £800", List.of(14));
        assertTrue(FactMatcher.found("a penalty of £800", outcome, List.of("£800")));
    }

    @Test
    @DisplayName("Normalisation folds compatibility characters, markdown bold, spaced numbers and whitespace")
    void normalises() {
        assertEquals("peak 9.4°c, total £4,149 here", FactMatcher.normalise("Peak **9.4℃**,\n total £4, 149   here"));
        assertEquals("april 7, 2025", FactMatcher.normalise("April 7, 2025"));
        assertTrue(FactMatcher.found("peak of 9.4℃", new CorpusFact(FactType.TEXT, "9.4°C", List.of(5)), List.of()));
    }
}
