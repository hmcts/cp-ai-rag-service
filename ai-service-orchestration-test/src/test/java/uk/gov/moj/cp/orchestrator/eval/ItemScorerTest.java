package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemScorerTest {

    private static final String CALDER = "11111111-1111-1111-1111-111111111111";
    private static final String PEMBERTON = "22222222-2222-2222-2222-222222222222";
    private static final String NOTE_A = "33333333-3333-3333-3333-333333333333";
    private static final String NOTE_B = "44444444-4444-4444-4444-444444444444";
    private static final String ADDENDUM = "55555555-5555-5555-5555-555555555555";

    private static final EvalDataset DATASET = EvalDataset.loadDefault();
    private static final ItemScorer SCORER = new ItemScorer(DATASET, Map.of(
            CALDER, "supplier-review-calder.pdf",
            PEMBERTON, "supplier-review-pemberton.pdf",
            NOTE_A, "site-inspection-note-a.pdf",
            NOTE_B, "site-inspection-note-b.pdf",
            ADDENDUM, "site-inspection-note-with-addendum.pdf"));

    private static final List<MetadataEntry> CALDER_METADATA = List.of(
            new MetadataEntry("corpus", "eval-v1"), new MetadataEntry("set", "supplier-reviews"), new MetadataEntry("supplier", "calder"));
    private static final List<MetadataEntry> NOTE_METADATA = List.of(
            new MetadataEntry("corpus", "eval-v1"), new MetadataEntry("set", "inspection-notes"));

    private static String cite(final String file, final int page, final String documentId) {
        return " ::(Source: [" + file + "], Pages " + page + "|" + page + "|documentId=" + documentId + ")";
    }

    private static AnswerResult answer(final String text, final List<RetrievedChunk> chunks) {
        return new AnswerResult("ANSWER_GENERATED", text, null, chunks, 12.5);
    }

    @Test
    @DisplayName("A correct, cited answer scores full fact recall, gold-page recall and citation precision")
    void scoresCorrectAnswer() {
        final ItemScore score = SCORER.score(DATASET.item("plain-001"), answer(
                "The logger recorded 9.4℃ and its serial number is TL-55821" + cite("supplier-review-calder.pdf", 5, CALDER) + ".",
                List.of(new RetrievedChunk(CALDER, 5, CALDER_METADATA), new RetrievedChunk(CALDER, 7, CALDER_METADATA))));

        assertEquals(1.0, score.factRecall());
        assertTrue(score.factRecallPass());
        assertEquals(List.of(), score.missingFacts());
        assertEquals(1.0, score.goldPageRecall());
        assertEquals(1.0, score.citationPrecision());
        assertTrue(score.citationPrecisionGated());
        assertFalse(score.citationDegraded());
        assertEquals(0, score.chunksOutsideFilter());
        assertTrue(score.refusalCorrect());
        assertEquals(2, score.chunks());
    }

    @Test
    @DisplayName("A value from the look-alike document is a forbidden hit, and a missing fact is listed")
    void flagsForbiddenAndMissing() {
        final ItemScore score = SCORER.score(DATASET.item("lookalike-001"), answer(
                "The peak was 9.4°C" + cite("supplier-review-pemberton.pdf", 5, PEMBERTON) + ".",
                List.of(new RetrievedChunk(PEMBERTON, 5, CALDER_METADATA))));

        assertEquals(0.0, score.factRecall());
        assertFalse(score.factRecallPass());
        assertEquals(List.of("pemberton-peak-temp", "pemberton-logger-serial"), score.missingFacts());
        assertEquals(List.of("supplier-review-calder.pdf#calder-peak-temp"), score.forbiddenHits());
    }

    @Test
    @DisplayName("A forbidden text is matched after normalisation")
    void matchesForbiddenText() {
        final ItemScore score = SCORER.score(DATASET.item("history-001"), answer("A **three-month   suspension** was imposed.", List.of()));

        assertEquals(List.of("three-month suspension"), score.forbiddenHits());
    }

    @Test
    @DisplayName("Refusal: an uncited refusal passes a should-refuse item and has no fact or citation measures")
    void scoresRefusal() {
        final ItemScore score = SCORER.score(DATASET.item("refusal-001"), answer(
                "The retrieved documents do not contain an insurance policy number.", List.of(new RetrievedChunk(CALDER, 3, CALDER_METADATA))));

        assertTrue(score.refused());
        assertTrue(score.refusalCorrect());
        assertNull(score.factRecall());
        assertNull(score.goldPageRecall());
        assertNull(score.citationPrecision());
    }

    @Test
    @DisplayName("Refusing an answerable item is a refusal error")
    void flagsWrongRefusal() {
        final ItemScore score = SCORER.score(DATASET.item("plain-001"), answer("No data available matching the query.", List.of()));

        assertTrue(score.refused());
        assertFalse(score.refusalCorrect());
    }

    @Test
    @DisplayName("Retrieval checks: the superset is required, at most one duplicate copy may survive")
    void checksRetrieval() {
        final ItemScore ok = SCORER.score(DATASET.item("dedup-001"), answer("x", List.of(
                new RetrievedChunk(ADDENDUM, 1, NOTE_METADATA), new RetrievedChunk(NOTE_A, 1, NOTE_METADATA))));
        final ItemScore bothCopies = SCORER.score(DATASET.item("dedup-001"), answer("x", List.of(
                new RetrievedChunk(ADDENDUM, 1, NOTE_METADATA), new RetrievedChunk(NOTE_A, 1, NOTE_METADATA),
                new RetrievedChunk(NOTE_B, 1, NOTE_METADATA))));
        final ItemScore noSuperset = SCORER.score(DATASET.item("dedup-001"), answer("x", List.of(new RetrievedChunk(NOTE_A, 1, NOTE_METADATA))));

        assertTrue(ok.retrievalOk());
        assertEquals(List.of("site-inspection-note-a.pdf", "site-inspection-note-with-addendum.pdf"), ok.retrievedDocuments());
        assertFalse(bothCopies.retrievalOk());
        assertFalse(noSuperset.retrievalOk());
        assertNull(SCORER.score(DATASET.item("plain-001"), answer("x", List.of())).retrievalOk());
    }

    @Test
    @DisplayName("A chunk whose metadata does not satisfy the item's filter is counted")
    void countsChunksOutsideFilter() {
        final ItemScore score = SCORER.score(DATASET.item("dedup-001"), answer("x", List.of(
                new RetrievedChunk(ADDENDUM, 1, NOTE_METADATA), new RetrievedChunk(CALDER, 5, CALDER_METADATA))));

        assertEquals(1, score.chunksOutsideFilter());
    }

    @Test
    @DisplayName("Long items: recall is judged against the item threshold and citation precision is report-only")
    void appliesLongItemRules() {
        final ItemScore score = SCORER.score(DATASET.item("summary-001"), answer(
                "## Findings\nOn 17/04/2025 ...\n## Losses\nTotal £4,149" + cite("supplier-review-calder.pdf", 5, CALDER),
                List.of(new RetrievedChunk(CALDER, 5, CALDER_METADATA))));

        assertFalse(score.factRecallPass());
        assertFalse(score.citationPrecisionGated());
        assertEquals(2, score.sections());
        assertEquals(1, score.sectionsWithoutCitation());
        assertNull(SCORER.score(DATASET.item("plain-001"), answer("x", List.of())).sections());
    }

    @Test
    @DisplayName("A guard reason marks the answer citation-degraded, and the status is carried through")
    void carriesReasonAndStatus() {
        final ItemScore score = SCORER.score(DATASET.item("columns-001"), new AnswerResult("ANSWER_GENERATED", "x",
                "[guard=off] Citations missing: jsonBlock=true, inlineMarkers=0", List.of(), 8.0));

        assertTrue(score.citationDegraded());
        assertEquals("ANSWER_GENERATED", score.status());
        assertEquals(8.0, score.elapsedSeconds());
    }
}
