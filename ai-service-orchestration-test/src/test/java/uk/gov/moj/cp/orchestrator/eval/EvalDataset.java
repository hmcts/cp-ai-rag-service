package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.CorpusFact.FactType;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.ExpectedFact;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Forbidden;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.RetrievalCheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The golden dataset ({@code eval/dataset.json}) joined with its corpus manifest. Loading resolves
 * every fact reference, so a dataset that points at a missing fact fails here, before any
 * document is ingested.
 */
public record EvalDataset(String version, Path directory, List<DocumentSpec> documents, List<EvalItem> items,
                          Map<FactRef, CorpusFact> facts) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A corpus document and the metadata it is ingested with. */
    public record DocumentSpec(String file, List<MetadataEntry> metadata) {
    }

    /** Loads the dataset committed under {@code src/test/resources/eval}. */
    public static EvalDataset loadDefault() {
        try {
            return load(Path.of(EvalDataset.class.getResource("/eval/dataset.json").toURI()).getParent());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static EvalDataset load(final Path directory) {
        final JsonNode root = read(directory.resolve("dataset.json"));
        final Map<FactRef, CorpusFact> facts = loadManifest(read(directory.resolve(root.path("manifest").asText())));
        final String defaultPrompt = root.path("defaults").path("queryPrompt").asText();

        final List<DocumentSpec> documents = list(root.path("documents"),
                d -> new DocumentSpec(d.path("file").asText(), list(d.path("metadata"), EvalDataset::metadata)));
        final List<EvalItem> items = list(root.path("items"), i -> item(i, defaultPrompt));

        final List<String> unresolved = new ArrayList<>();
        for (final EvalItem item : items) {
            item.expectedFacts().forEach(f -> f.refs().stream().filter(r -> !facts.containsKey(r))
                    .forEach(r -> unresolved.add(item.id() + ": " + r)));
            item.forbidden().stream().map(Forbidden::ref).filter(r -> r != null && !facts.containsKey(r))
                    .forEach(r -> unresolved.add(item.id() + ": " + r));
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Dataset references facts missing from the manifest: " + unresolved);
        }
        return new EvalDataset(root.path("version").asText(), directory, documents, items, facts);
    }

    public CorpusFact fact(final FactRef ref) {
        return facts.get(ref);
    }

    public EvalItem item(final String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No dataset item " + id));
    }

    public Path documentPath(final String file) {
        return directory.resolve("documents").resolve(file);
    }

    private static Map<FactRef, CorpusFact> loadManifest(final JsonNode manifest) {
        final Map<FactRef, CorpusFact> facts = new HashMap<>();
        for (final JsonNode document : manifest.path("documents")) {
            final String file = document.path("file").asText();
            document.path("facts").fields().forEachRemaining(e -> facts.put(new FactRef(file, e.getKey()), new CorpusFact(
                    FactType.of(e.getValue().path("type").asText()),
                    e.getValue().path("value").asText(),
                    list(e.getValue().path("pages"), JsonNode::asInt))));
        }
        return facts;
    }

    private static EvalItem item(final JsonNode node, final String defaultPrompt) {
        final JsonNode retrieval = node.path("retrieval");
        return new EvalItem(
                node.path("id").asText(),
                node.path("behaviour").asText(),
                list(node.path("tier"), JsonNode::asText),
                node.path("query").asText(),
                node.hasNonNull("queryPrompt") ? node.get("queryPrompt").asText() : defaultPrompt,
                list(node.path("metadataFilter"), EvalDataset::metadata),
                list(node.path("expectedFacts"), f -> new ExpectedFact(refs(f.path("ref")), list(f.path("accept"), JsonNode::asText))),
                list(node.path("forbidden"), f -> new Forbidden(
                        f.hasNonNull("ref") ? FactRef.parse(f.get("ref").asText()) : null,
                        f.hasNonNull("text") ? f.get("text").asText() : null)),
                retrieval.isMissingNode() ? null : new RetrievalCheck(
                        list(retrieval.path("mustInclude"), JsonNode::asText),
                        list(retrieval.path("atMostOneOf"), g -> list(g, JsonNode::asText))),
                node.path("shouldRefuse").asBoolean(),
                node.path("minFactRecall").asDouble(1.0),
                node.path("citations").path("perSection").asBoolean());
    }

    private static List<FactRef> refs(final JsonNode ref) {
        return ref.isArray() ? list(ref, r -> FactRef.parse(r.asText())) : List.of(FactRef.parse(ref.asText()));
    }

    private static MetadataEntry metadata(final JsonNode node) {
        return new MetadataEntry(node.path("key").asText(), node.path("value").asText());
    }

    private static <T> List<T> list(final JsonNode array, final Function<JsonNode, T> mapper) {
        final List<T> out = new ArrayList<>();
        array.forEach(n -> out.add(mapper.apply(n)));
        return List.copyOf(out);
    }

    private static JsonNode read(final Path path) {
        try {
            return MAPPER.readTree(path.toFile());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
