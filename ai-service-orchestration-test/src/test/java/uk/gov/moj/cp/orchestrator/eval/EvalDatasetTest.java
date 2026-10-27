package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Test
    @DisplayName("Every referenced fact exists in the manifest, and every referenced file is a corpus document with a PDF")
    void referencesResolve() {
        final Set<String> files = new HashSet<>();
        DATASET.documents().forEach(d -> files.add(d.file()));
        for (final EvalItem item : DATASET.items()) {
            item.expectedFacts().forEach(f -> f.refs().forEach(r -> assertNotNull(DATASET.fact(r), item.id() + " " + r)));
            item.forbidden().stream().filter(f -> f.ref() != null).forEach(f -> assertNotNull(DATASET.fact(f.ref()), item.id()));
            if (item.retrieval() != null) {
                item.retrieval().mustInclude().forEach(f -> assertTrue(files.contains(f), item.id() + " " + f));
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
    @DisplayName("A reference to a missing fact fails at load time")
    void rejectsUnknownFact(@TempDir final Path dir) throws Exception {
        Files.createDirectories(dir.resolve("documents"));
        Files.writeString(dir.resolve("documents/m.json"),
                "{\"documents\":[{\"file\":\"a.pdf\",\"facts\":{\"x\":{\"type\":\"text\",\"value\":\"v\",\"pages\":[1]}}}]}");
        Files.writeString(dir.resolve("dataset.json"), """
                {"version":"t","manifest":"documents/m.json","defaults":{"queryPrompt":"p"},
                 "documents":[{"file":"a.pdf","metadata":[{"key":"k","value":"v"}]}],
                 "items":[{"id":"i","query":"q","metadataFilter":[{"key":"k","value":"v"}],
                           "expectedFacts":[{"ref":"a.pdf#missing"}],"forbidden":[],"shouldRefuse":false}]}
                """);

        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> EvalDataset.load(dir));
        assertTrue(e.getMessage().contains("a.pdf#missing"));
    }
}
