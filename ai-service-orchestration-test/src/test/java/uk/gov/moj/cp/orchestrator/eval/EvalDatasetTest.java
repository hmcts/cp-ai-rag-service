package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the committed dataset is internally consistent, so a broken reference fails offline, not mid-run. */
class EvalDatasetTest {

    private static final EvalDataset DATASET = EvalDataset.loadDefault();

    @Test
    @DisplayName("Every item has a unique id, a query, a prompt and a non-empty filter")
    void itemsAreWellFormed() {
        final Set<String> ids = new HashSet<>();
        for (final EvalItem item : DATASET.items()) {
            assertTrue(ids.add(item.id()), "duplicate id " + item.id());
            assertFalse(item.query().isBlank(), item.id());
            assertFalse(item.queryPrompt().isBlank(), item.id());
            assertFalse(item.metadataFilter().isEmpty(), item.id());
            assertTrue(item.shouldRefuse() == item.expectedFacts().isEmpty(), item.id() + ": refusal items have no facts");
        }
    }

    private static int manifestPages(final String file) {
        try {
            for (final JsonNode document : new ObjectMapper().readTree(DATASET.directory().resolve("documents/corpus-manifest.json").toFile())
                    .path("documents")) {
                if (document.path("file").asText().equals(file)) {
                    return document.path("pages").asInt();
                }
            }
            return 0;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("Every referenced fact exists in the manifest, and every referenced file (or file#page) is a corpus document with a PDF")
    void referencesResolve() {
        final Set<String> files = new HashSet<>();
        DATASET.documents().forEach(d -> files.add(d.file()));
        for (final EvalItem item : DATASET.items()) {
            item.expectedFacts().forEach(f -> f.refs().forEach(r -> assertNotNull(DATASET.fact(r), item.id() + " " + r)));
            item.forbidden().stream().filter(f -> f.ref() != null).forEach(f -> assertNotNull(DATASET.fact(f.ref()), item.id()));
            if (item.retrieval() != null) {
                item.retrieval().mustInclude().forEach(f -> assertTrue(f.contains("#")
                        ? files.contains(f.substring(0, f.indexOf('#')))
                                && Integer.parseInt(f.substring(f.indexOf('#') + 1)) <= manifestPages(f.substring(0, f.indexOf('#')))
                        : files.contains(f), item.id() + " " + f));
                item.retrieval().atMostOneOf().forEach(g -> assertTrue(files.containsAll(g), item.id() + " " + g));
            }
        }
        for (final String file : files) {
            assertTrue(Files.isRegularFile(DATASET.documentPath(file)), file);
        }
    }

    @Test
    @DisplayName("Item defaults apply: the dataset prompt unless overridden, and full recall unless a threshold is set")
    void appliesDefaults() {
        final EvalItem plain = DATASET.item("plain-001");
        final EvalItem summary = DATASET.item("summary-001");

        assertTrue(plain.queryPrompt().startsWith("State names, dates and values exactly"));
        assertEquals(1.0, plain.minFactRecall());
        assertFalse(plain.citationsPerSection());
        assertTrue(summary.queryPrompt().startsWith("Answer in sections"));
        assertEquals(0.8, summary.minFactRecall());
        assertTrue(summary.citationsPerSection());
        assertEquals(List.of("smoke", "full"), plain.tiers());
    }

    @Test
    @DisplayName("A ref list is kept as alternatives")
    void keepsAlternativeRefs() {
        assertEquals(3, DATASET.item("dedup-002").expectedFacts().get(0).refs().size());
    }

    @Test
    @DisplayName("Anchors, key words and forbidden claims are loaded")
    void loadsBindingAndClaims() {
        final EvalItem.ExpectedFact outcome = DATASET.item("history-001").expectedFacts().get(5);
        final EvalItem.ExpectedFact latest = DATASET.item("injection-001").expectedFacts().get(0);

        assertEquals("calder-hist-8-date", outcome.near().ref().factId());
        assertEquals(List.of("suspen"), outcome.keywords().get(0));
        assertEquals(List.of("most recent", "latest"), latest.near().text());
        assertEquals(2, latest.near().rivals().size());
        assertEquals(List.of("all inspections were passed", "no breaches have ever been recorded"),
                DATASET.item("injection-001").forbidden().stream().map(EvalItem.Forbidden::label).toList());
    }

    @Test
    @DisplayName("A reference to a missing fact fails at load time, including an anchor or a rival")
    void rejectsUnknownFact(@TempDir final Path dir) throws Exception {
        for (final String fact : List.of("{\"ref\":\"a.pdf#missing\"}", "{\"ref\":\"a.pdf#x\",\"near\":{\"ref\":\"a.pdf#missing\"}}",
                "{\"ref\":\"a.pdf#x\",\"near\":{\"text\":[\"t\"],\"rivals\":[\"a.pdf#missing\"]}}")) {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> EvalDataset.load(dataset(dir, fact, "")));
            assertTrue(e.getMessage().contains("a.pdf#missing"), fact);
        }
    }

    @Test
    @DisplayName("A malformed anchor, key-word group or forbidden entry fails at load time")
    void rejectsMalformedEntries(@TempDir final Path dir) throws Exception {
        for (final String[] entry : new String[][] {
                {"{\"ref\":\"a.pdf#x\",\"near\":{\"ref\":\"a.pdf#x\",\"text\":[\"t\"]}}", ""},
                {"{\"ref\":\"a.pdf#x\",\"near\":{}}", ""},
                {"{\"ref\":\"a.pdf#x\",\"keywords\":[[]]}", ""},
                {"{\"ref\":\"a.pdf#x\"}", "{\"claim\":\"c\",\"patterns\":[]}"},
                {"{\"ref\":\"a.pdf#x\"}", "{\"claim\":\"c\",\"patterns\":[\"(\"]}"}}) {
            assertThrows(RuntimeException.class, () -> EvalDataset.load(dataset(dir, entry[0], entry[1])), entry[0] + entry[1]);
        }
    }

    private static Path dataset(final Path dir, final String expectedFact, final String forbidden) throws Exception {
        Files.createDirectories(dir.resolve("documents"));
        Files.writeString(dir.resolve("documents/m.json"),
                "{\"documents\":[{\"file\":\"a.pdf\",\"facts\":{\"x\":{\"type\":\"text\",\"value\":\"v\",\"pages\":[1]}}}]}");
        Files.writeString(dir.resolve("dataset.json"), """
                {"version":"t","manifest":"documents/m.json","defaults":{"queryPrompt":"p"},
                 "documents":[{"file":"a.pdf","metadata":[{"key":"k","value":"v"}]}],
                 "items":[{"id":"i","query":"q","metadataFilter":[{"key":"k","value":"v"}],
                           "expectedFacts":[%s],"forbidden":[%s],"shouldRefuse":false}]}
                """.formatted(expectedFact, forbidden));
        return dir;
    }
}
