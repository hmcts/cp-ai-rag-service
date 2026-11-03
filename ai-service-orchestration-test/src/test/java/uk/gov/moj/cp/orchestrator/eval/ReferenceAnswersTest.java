package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalDataset.DocumentSpec;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final EvalDataset DATASET = EvalDataset.loadDefault();
    private static final Map<String, String> ID_BY_FILE = DATASET.documents().stream()
            .collect(Collectors.toMap(DocumentSpec::file, d -> UUID.nameUUIDFromBytes(d.file().getBytes()).toString()));
    private static final Map<String, List<MetadataEntry>> METADATA_BY_FILE = DATASET.documents().stream()
            .collect(Collectors.toMap(DocumentSpec::file, DocumentSpec::metadata));
    private static final ItemScorer SCORER = new ItemScorer(DATASET, ID_BY_FILE.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)));
    private static final EvalGate GATE = new EvalGate(KnownIssues.of(DATASET, List.of()));
    private static final Pattern CITE = Pattern.compile("\\{cite:([^#}]+)#([\\d,]+)}");
    private static final JsonNode REFERENCES = read().path("items");

    @Test
    @DisplayName("Every dataset item has at least one passing and one failing reference answer, and every entry names an item")
    void coversEveryItem() {
        final Set<String> ids = DATASET.items().stream().map(EvalItem::id).collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<String> referenced = new LinkedHashSet<>();
        REFERENCES.fieldNames().forEachRemaining(referenced::add);

        assertEquals(ids, referenced);
        assertAll(ids.stream().map(id -> () -> {
            assertFalse(REFERENCES.path(id).path("pass").isEmpty(), id + " has no passing reference answer");
            assertFalse(REFERENCES.path(id).path("fail").isEmpty(), id + " has no failing reference answer");
        }));
    }

    @TestFactory
    @DisplayName("Each correct reference answer passes the gate")
    Stream<DynamicTest> correctAnswersPass() {
        return answers("pass").map(a -> dynamicTest(a.itemId() + ": " + a.label(),
                () -> assertEquals(List.of(), outcome(a).failures(), a.label())));
    }

    @TestFactory
    @DisplayName("Each wrong reference answer fails exactly the listed checks")
    Stream<DynamicTest> wrongAnswersFail() {
        return answers("fail").map(a -> dynamicTest(a.itemId() + ": " + a.label(),
                () -> assertEquals(a.failures(), outcome(a).failures().stream().map(f -> f.substring(0, f.indexOf(':'))).toList(),
                        a.label())));
    }

    private record Reference(String itemId, String label, String answer, List<String> retrieved, List<String> failures) {
    }

    private static EvalGate.RunOutcome outcome(final Reference reference) {
        final EvalItem item = DATASET.item(reference.itemId());
        final List<RetrievedChunk> chunks = reference.retrieved().stream().map(p -> {
            final String file = p.substring(0, p.indexOf('#'));
            return new RetrievedChunk(ID_BY_FILE.get(file), Integer.parseInt(p.substring(p.indexOf('#') + 1)), METADATA_BY_FILE.get(file));
        }).toList();
        final ItemScore score = SCORER.score(item, new AnswerResult("ANSWER_GENERATED", render(reference.answer()), null, chunks, 10.0));
        return GATE.outcome(item, new ItemRun(item.id(), 1, "t1", null, score, reference.answer(), null));
    }

    /** {@code {cite:file#3,5}} becomes {@code ::(Source: [file], Pages 3,5|3,5|documentId=<id>)}, as CitationProcessor renders it. */
    private static String render(final String answer) {
        final Matcher m = CITE.matcher(answer);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement("::(Source: [" + m.group(1) + "], Pages " + m.group(2) + "|" + m.group(2)
                    + "|documentId=" + ID_BY_FILE.get(m.group(1)) + ")"));
        }
        return m.appendTail(out).toString();
    }

    private static Stream<Reference> answers(final String kind) {
        final List<Reference> references = new ArrayList<>();
        REFERENCES.properties().forEach(item -> item.getValue().path(kind).forEach(node -> references.add(new Reference(
                item.getKey(),
                node.path("label").asText(),
                node.path("answer").asText(),
                node.has("retrieved") ? strings(node.path("retrieved")) : goldPages(item.getKey()),
                strings(node.path("failures"))))));
        return references.stream();
    }

    private static List<String> goldPages(final String itemId) {
        final Set<String> pages = new LinkedHashSet<>();
        DATASET.item(itemId).expectedFacts().forEach(f -> {
            final FactRef ref = f.refs().get(0);
            DATASET.fact(ref).pages().forEach(p -> pages.add(ref.file() + "#" + p));
        });
        return List.copyOf(pages);
    }

    private static List<String> strings(final JsonNode array) {
        final List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static JsonNode read() {
        try {
            return new ObjectMapper().readTree(Files.readString(DATASET.directory().resolve("reference-answers.json")));
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read reference-answers.json", e);
        }
    }
}
