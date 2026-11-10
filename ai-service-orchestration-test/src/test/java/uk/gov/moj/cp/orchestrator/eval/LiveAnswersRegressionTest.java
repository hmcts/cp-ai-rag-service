package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalDataset.DocumentSpec;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rescores every distinct answer the live service gave in the saved eval runs ({@code live-answers.json})
 * and compares each gate outcome, with no known issues applied, with the committed verdict in
 * {@code live-answers-expected.json}. A scorer or dataset change that alters any verdict fails
 * here with the full list of changes, so each one can be reviewed against the real answer.
 *
 * <p>Runs without recorded retrieval are scored without their retrieval check. To refresh the
 * expected file after a reviewed change, run with {@code -Deval.liveAnswers.write=<path>} and
 * copy the written file over it.</p>
 */
class LiveAnswersRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final EvalDataset DATASET = EvalDataset.loadDefault();
    private static final Map<String, List<MetadataEntry>> METADATA_BY_FILE = DATASET.documents().stream()
            .collect(Collectors.toMap(DocumentSpec::file, DocumentSpec::metadata));
    private static final EvalGate GATE = new EvalGate(KnownIssues.of(DATASET, List.of()));

    @Test
    @DisplayName("Rescoring the saved live answers gives the committed verdict for every answer")
    void verdictsAreUnchanged() throws IOException {
        final JsonNode live = read("live-answers.json");
        final Map<String, List<String>> actual = new LinkedHashMap<>();
        live.path("answers").forEach(a -> actual.put(key(a), failures(live.path("runs").path(a.path("run").asText()), a)));

        final String write = System.getProperty("eval.liveAnswers.write");
        if (write != null) {
            MAPPER.writeValue(Path.of(write).toFile(), actual);
        }

        final Map<String, List<String>> expected = MAPPER.convertValue(read("live-answers-expected.json"),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, MAPPER.getTypeFactory().constructType(String.class),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        final Set<String> unmatched = new TreeSet<>(expected.keySet());
        unmatched.addAll(actual.keySet());
        unmatched.removeIf(k -> expected.containsKey(k) && actual.containsKey(k));
        assertEquals(Set.of(), unmatched, "live-answers-expected.json must have exactly one entry per saved answer");
        final List<String> changed = new ArrayList<>();
        actual.forEach((key, failures) -> {
            if (!failures.equals(expected.get(key))) {
                changed.add(key + "\n    expected " + expected.get(key) + "\n    actual   " + failures);
            }
        });
        if (!changed.isEmpty()) {
            fail(changed.size() + " saved live answer(s) changed verdict:\n  " + String.join("\n  ", changed));
        }
    }

    private static List<String> failures(final JsonNode run, final JsonNode answer) {
        final Map<String, String> fileById = new LinkedHashMap<>();
        run.path("documentIds").properties().forEach(e -> fileById.put(e.getValue().asText(), e.getKey()));
        final Map<String, String> idByFile = fileById.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        final List<RetrievedChunk> chunks = new ArrayList<>();
        answer.path("retrieved").forEach(p -> {
            final String page = p.asText();
            final String file = page.substring(0, page.indexOf('#'));
            chunks.add(new RetrievedChunk(idByFile.getOrDefault(file, file), Integer.parseInt(page.substring(page.indexOf('#') + 1)),
                    METADATA_BY_FILE.get(file)));
        });
        final EvalItem item = DATASET.item(answer.path("item").asText());
        final AnswerResult result = new AnswerResult(answer.path("status").asText("ANSWER_GENERATED"), answer.path("answer").asText(),
                answer.path("reason").isTextual() ? answer.path("reason").asText() : null, chunks, 10.0);
        final ItemScore score = new ItemScorer(DATASET, fileById).score(item, result);
        final List<String> failures = GATE.outcome(item, new ItemRun(item.id(), answer.path("attempt").asInt(), "t", null, score,
                result.llmResponse(), null)).failures();
        return run.path("retrievalRecorded").asBoolean()
                ? failures
                : failures.stream().filter(f -> !f.startsWith("retrieval:")).toList();
    }

    private static String key(final JsonNode answer) {
        return answer.path("run").asText() + "/" + answer.path("item").asText() + "/" + answer.path("attempt").asInt();
    }

    private static JsonNode read(final String name) throws IOException {
        return MAPPER.readTree(Files.readString(DATASET.directory().resolve(name)));
    }
}
