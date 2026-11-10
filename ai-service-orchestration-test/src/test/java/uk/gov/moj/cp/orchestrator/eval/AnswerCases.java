package uk.gov.moj.cp.orchestrator.eval;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads an answers file ({@code reference-answers.json}, {@code live-answers.json}) and scores each answer
 * through the eval's scorer and gate with no known issues applied. Both files share one format: per item,
 * {@code pass} answers that must pass every gated check and {@code fail} answers that must fail exactly the
 * checks listed, with optional {@code retrieved} chunks, guard {@code reason} and {@code status}.
 */
final class AnswerCases {

    static final EvalDataset DATASET = EvalDataset.loadDefault();
    private static final Map<String, String> ID_BY_FILE = DATASET.documents().stream()
            .collect(Collectors.toMap(DocumentSpec::file, d -> UUID.nameUUIDFromBytes(d.file().getBytes()).toString()));
    private static final Map<String, List<MetadataEntry>> METADATA_BY_FILE = DATASET.documents().stream()
            .collect(Collectors.toMap(DocumentSpec::file, DocumentSpec::metadata));
    private static final ItemScorer SCORER = new ItemScorer(DATASET, ID_BY_FILE.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)));
    private static final EvalGate GATE = new EvalGate(KnownIssues.of(DATASET, List.of()));
    private static final Pattern CITE = Pattern.compile("\\{cite:([^#}]+)#([\\d,]+)}");
    private static final String RETRIEVAL = "retrieval";

    /** What to assume when an answer has no {@code retrieved} list. */
    enum MissingRetrieval {
        /** The gold pages of the item's expected facts and the pages its retrieval check requires (hand-written answers). */
        GOLD_PAGES,
        /** Not recorded: no chunks, and the retrieval check is not scored (live answers from runs that kept no retrieval). */
        NOT_RECORDED
    }

    record Case(String itemId, String kind, String label, String answer, List<String> retrieved, String reason, String status,
                List<String> failures) {
    }

    private AnswerCases() {
    }

    static JsonNode items(final String file) {
        try {
            return new ObjectMapper().readTree(Files.readString(DATASET.directory().resolve(file))).path("items");
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    static List<Case> read(final String file, final String kind) {
        final List<Case> cases = new ArrayList<>();
        items(file).properties().forEach(item -> item.getValue().path(kind).forEach(node -> cases.add(new Case(
                item.getKey(),
                kind,
                node.path("label").asText(),
                node.path("answer").asText(),
                node.has("retrieved") ? strings(node.path("retrieved")) : null,
                node.hasNonNull("reason") ? node.get("reason").asText() : null,
                node.path("status").asText("ANSWER_GENERATED"),
                strings(node.path("failures"))))));
        return cases;
    }

    /** The checks the answer fails (the part of each gate failure before its colon), in the gate's order. */
    static List<String> failedChecks(final Case c, final MissingRetrieval missing) {
        final EvalItem item = DATASET.item(c.itemId());
        final boolean notRecorded = c.retrieved() == null && missing == MissingRetrieval.NOT_RECORDED;
        final List<String> pages = c.retrieved() != null ? c.retrieved() : notRecorded ? List.of() : goldPages(item);
        final List<RetrievedChunk> chunks = pages.stream().map(p -> {
            final String file = p.substring(0, p.indexOf('#'));
            return new RetrievedChunk(ID_BY_FILE.getOrDefault(file, file), Integer.parseInt(p.substring(p.indexOf('#') + 1)),
                    METADATA_BY_FILE.get(file));
        }).toList();
        final ItemScore score = SCORER.score(item, new AnswerResult(c.status(), render(c.answer()), c.reason(), chunks, 10.0));
        return GATE.outcome(item, new ItemRun(item.id(), 1, "t1", null, score, c.answer(), null)).failures().stream()
                .map(f -> f.substring(0, f.indexOf(':')))
                .filter(check -> !(notRecorded && RETRIEVAL.equals(check)))
                .toList();
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

    private static List<String> goldPages(final EvalItem item) {
        final Set<String> pages = new LinkedHashSet<>();
        item.expectedFacts().forEach(f -> {
            final FactRef ref = f.refs().get(0);
            DATASET.fact(ref).pages().forEach(p -> pages.add(ref.file() + "#" + p));
        });
        if (item.retrieval() != null) {
            item.retrieval().mustInclude().stream().filter(m -> m.contains("#")).forEach(pages::add);
        }
        return List.copyOf(pages);
    }

    private static List<String> strings(final JsonNode array) {
        final List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
