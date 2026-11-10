package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.CorpusFact.FactType;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.ExpectedFact;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Forbidden;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Near;
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
import java.util.regex.Pattern;

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
    private static final String QUERY_PROMPT_FIELD = "queryPrompt";
    private static final String DOCUMENTS_FIELD = "documents";
    private static final String TEXT_FIELD = "text";

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
        final String defaultPrompt = root.path("defaults").path(QUERY_PROMPT_FIELD).asText();

        final List<DocumentSpec> documents = list(root.path(DOCUMENTS_FIELD),
                d -> new DocumentSpec(d.path("file").asText(), list(d.path("metadata"), EvalDataset::metadata)));
        final List<EvalItem> items = list(root.path("items"), i -> item(i, defaultPrompt));

        final List<String> unresolved = new ArrayList<>();
        for (final EvalItem item : items) {
            item.expectedFacts().forEach(f -> referencedBy(f).stream().filter(r -> !facts.containsKey(r))
                    .forEach(r -> unresolved.add(item.id() + ": " + r)));
            item.forbidden().stream().map(Forbidden::ref).filter(r -> r != null && !facts.containsKey(r))
                    .forEach(r -> unresolved.add(item.id() + ": " + r));
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Dataset references facts missing from the manifest: " + unresolved);
        }
        return new EvalDataset(root.path("version").asText(), directory, documents, items, Map.copyOf(facts));
    }

    public CorpusFact fact(final FactRef ref) {
        return facts.get(ref);
    }

    public EvalItem item(final String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No dataset item " + id));
    }

    public Path documentPath(final String file) {
        return directory.resolve(DOCUMENTS_FIELD).resolve(file);
    }

    private static Map<FactRef, CorpusFact> loadManifest(final JsonNode manifest) {
        final Map<FactRef, CorpusFact> facts = new HashMap<>();
        for (final JsonNode document : manifest.path(DOCUMENTS_FIELD)) {
            final String file = document.path("file").asText();
            document.path("facts").properties().forEach(e -> facts.put(new FactRef(file, e.getKey()), new CorpusFact(
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
                node.hasNonNull(QUERY_PROMPT_FIELD) ? node.get(QUERY_PROMPT_FIELD).asText() : defaultPrompt,
                list(node.path("metadataFilter"), EvalDataset::metadata),
                list(node.path("expectedFacts"), EvalDataset::expectedFact),
                list(node.path("forbidden"), EvalDataset::forbidden),
                retrieval.isMissingNode() ? null : new RetrievalCheck(
                        list(retrieval.path("mustInclude"), JsonNode::asText),
                        list(retrieval.path("atMostOneOf"), g -> list(g, JsonNode::asText))),
                node.path("shouldRefuse").asBoolean(),
                node.path("minFactRecall").asDouble(1.0),
                node.path("citations").path("perSection").asBoolean(),
                node.path("chronological").asBoolean());
    }

    private static ExpectedFact expectedFact(final JsonNode node) {
        final List<List<String>> keywords = list(node.path("keywords"), g -> list(g, JsonNode::asText));
        if (keywords.stream().anyMatch(List::isEmpty)) {
            throw new IllegalStateException("A keywords group is empty: " + node);
        }
        final JsonNode near = node.path("near");
        if (!near.isMissingNode() && near.hasNonNull("ref") == near.has(TEXT_FIELD)) {
            throw new IllegalStateException("near needs exactly one of ref or text: " + node);
        }
        return new ExpectedFact(refs(node.path("ref")), list(node.path("accept"), JsonNode::asText), keywords,
                near.isMissingNode() ? null : new Near(
                        near.hasNonNull("ref") ? FactRef.parse(near.get("ref").asText()) : null,
                        list(near.path(TEXT_FIELD), JsonNode::asText),
                        list(near.path("rivals"), r -> FactRef.parse(r.asText())),
                        list(near.path("rivalText"), JsonNode::asText)));
    }

    /** {@code ref} (a manifest fact), {@code text} (a literal phrase) or {@code claim} with {@code patterns} (regexes). */
    private static Forbidden forbidden(final JsonNode node) {
        if (node.hasNonNull("ref")) {
            return new Forbidden(FactRef.parse(node.get("ref").asText()), null, List.of());
        }
        if (node.hasNonNull(TEXT_FIELD)) {
            return new Forbidden(null, node.get(TEXT_FIELD).asText(), List.of(FactMatcher.literal(node.get(TEXT_FIELD).asText())));
        }
        final List<Pattern> patterns = list(node.path("patterns"), p -> Pattern.compile(p.asText()));
        if (!node.hasNonNull("claim") || patterns.isEmpty()) {
            throw new IllegalStateException("A forbidden entry needs ref, text, or claim with patterns: " + node);
        }
        return new Forbidden(null, node.get("claim").asText(), patterns);
    }

    /** Every manifest fact an expected fact points at: its refs, its anchor and the anchor's rivals. */
    private static List<FactRef> referencedBy(final ExpectedFact fact) {
        final List<FactRef> refs = new ArrayList<>(fact.refs());
        if (fact.near() != null) {
            if (fact.near().ref() != null) {
                refs.add(fact.near().ref());
            }
            refs.addAll(fact.near().rivals());
        }
        return refs;
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
