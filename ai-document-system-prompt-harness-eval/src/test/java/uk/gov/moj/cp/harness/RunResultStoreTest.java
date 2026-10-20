package uk.gov.moj.cp.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.harness.TestHarness.RunResult;
import uk.gov.moj.cp.retrieval.model.LlmResponse;

/**
 * Unit tests for the persisted shape of a harness run ({@link RunResultStore#document}): the
 * citation-validation counts and the {@code meta.citationValidation} marker, and that rows with
 * no compliance (SKIPPED / ERROR) persist without metrics. No file or environment I/O.
 */
class RunResultStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ChunkedEntry> CHUNKS = List.of(
            ChunkedEntry.builder().documentId("doc-A").documentFileName("report-a.pdf").pageNumber(2).build());

    private static RunResult okRun(final String raw) {
        final LlmResponse response = new LlmResponse(raw, "formatted", AnswerGenerationStatus.ANSWER_GENERATED);
        return new RunResult("p1", "m1", "q1", 1, response, null, 10L, null, CitationMetrics.compute(response, CHUNKS));
    }

    private static Map<String, Object> document(final RunResult... results) {
        return RunResultStore.document(List.of(results), List.of(), List.of(), "queries.json", "");
    }

    @Test
    void metricsCarryTheValidationCounts() {
        final RunResult run = okRun("The first item is listed [1]. The second item is listed [2]. <FACT_MAP_JSON>["
                + "{\"citationId\":1,\"documentId\":\"doc-A\",\"documentFilename\":\"report-a.pdf\",\"individualPageNumbers\":\"2,7\"},"
                + "{\"citationId\":2,\"documentId\":\"doc-B\",\"documentFilename\":\"notes-b.pdf\",\"individualPageNumbers\":\"5\"}"
                + "]</FACT_MAP_JSON>");

        final JsonNode row = MAPPER.valueToTree(document(run)).get("results").get(0);

        assertEquals(1, row.get("metrics").get("invalidCitations").asInt());
        assertEquals(1, row.get("metrics").get("trimmedPages").asInt());
        assertEquals(1, row.get("metrics").get("renderedCitations").asInt());
    }

    @Test
    void metaMarksTheFileAsProducedWithCitationValidation() {
        final JsonNode meta = MAPPER.valueToTree(document()).get("meta");

        assertTrue(meta.get("citationValidation").asBoolean());
    }

    @Test
    void skippedAndErrorRows_persistWithoutMetrics() {
        final RunResult skipped = new RunResult("p1", "m1", "q1", 1, null, null, 0L, "SKIPPED: no chunks for query", null);
        final RunResult error = new RunResult("p1", "m1", "q1", 2, null, null, 5L, "boom", null);

        final JsonNode results = MAPPER.valueToTree(document(skipped, error)).get("results");

        assertEquals(2, results.size());
        for (final JsonNode row : results) {
            assertFalse(row.has("metrics"), row.toString());
            assertTrue(row.get("status").isNull(), row.toString());
        }
    }

    @Test
    void responseWithoutCompliance_persistsWithoutMetrics() {
        final RunResult run = new RunResult("p1", "m1", "q1", 1,
                new LlmResponse("raw", "formatted", AnswerGenerationStatus.ANSWER_GENERATED), null, 1L, null, null);

        final JsonNode row = MAPPER.valueToTree(document(run)).get("results").get(0);

        assertEquals("raw", row.get("rawResponse").asText());
        assertFalse(row.has("metrics"));
    }

    @Test
    void persistedCount_isNotMeasuredWhenAbsent() throws Exception {
        final JsonNode oldRow = MAPPER.readTree("{\"metrics\":{\"renderedCitations\":2}}");
        final JsonNode newRow = MAPPER.readTree("{\"metrics\":{\"invalidCitations\":0,\"trimmedPages\":3}}");
        final JsonNode noMetrics = MAPPER.readTree("{\"status\":null}");

        assertEquals("n/a", RunResultStore.persistedCount(oldRow, "invalidCitations"));
        assertEquals("n/a", RunResultStore.persistedCount(noMetrics, "trimmedPages"));
        assertEquals("0", RunResultStore.persistedCount(newRow, "invalidCitations"));
        assertEquals("3", RunResultStore.persistedCount(newRow, "trimmedPages"));
    }

    @Test
    void oldAndNewResultFiles_bothLoadInTheBaselineJudge() throws Exception {
        final String oldFile = "{\"meta\":{\"queryFile\":\"queries.json\"},\"results\":[{\"iteration\":1,"
                + "\"queryLabel\":\"q1\",\"promptLabel\":\"p1\",\"llmLabel\":\"m1\",\"durationMs\":10,"
                + "\"status\":\"ANSWER_GENERATED\",\"error\":null,\"rawResponse\":\"raw\",\"formattedResponse\":\"f\","
                + "\"metrics\":{\"renderedCitations\":1}}]}";
        final RunResult run = okRun("raw text");
        final JsonNode newFile = MAPPER.valueToTree(document(run));

        assertEquals(1, BaselineJudgeTool.rowsOf(MAPPER.readTree(oldFile), "m1").size());
        assertEquals(1, BaselineJudgeTool.rowsOf(newFile, "m1").size());
        assertNull(MAPPER.readTree(oldFile).get("meta").get("citationValidation"));
    }
}
