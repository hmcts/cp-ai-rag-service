package uk.gov.moj.cp.orchestrator.eval;

import static java.util.UUID.randomUUID;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GROUNDEDNESS_SCORE;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.ContractValidator.assertMatchesContract;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.postRequest;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.moj.cp.orchestrator.DocumentIngestionFlow;
import uk.gov.moj.cp.orchestrator.FunctionAppName;
import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalDataset.DocumentSpec;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.DocumentIngestion;
import uk.gov.moj.cp.orchestrator.eval.EvalReport.ItemRun;
import uk.gov.moj.cp.orchestrator.extension.FunctionTestBase;
import uk.gov.moj.cp.orchestrator.extension.ServiceTuningEnv;
import uk.gov.moj.cp.orchestrator.util.BlobUtil;
import uk.gov.moj.cp.orchestrator.util.RestOperation;
import uk.gov.moj.cp.orchestrator.util.RestPoller;
import uk.gov.moj.cp.orchestrator.util.TableUtil;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone end-to-end evaluation (DD-43887, design D2). Ingests the synthetic corpus through
 * {@code /document-upload}, runs every selected dataset item N times through the async query
 * path, scores each answer and writes {@code target/eval/eval-report.json}.
 *
 * <p>M1 (every corpus document ingests) always asserts. With {@code eval.gate=true} the answers
 * are also gated (DD-43901, {@link EvalGate}): an item whose first run fails is re-run up to three
 * runs, at least two of which must pass; checks in {@code eval/known-issues.json} are reported but
 * not gated. Without it the run is report-only: answer quality is in the report and the log.</p>
 *
 * <p>Selection and size, as system properties: {@code eval.tier} ({@code full} by default, or
 * {@code smoke}), {@code eval.items} (comma-separated ids, overrides the tier), {@code eval.runs}
 * (default 1), {@code eval.parallelism} (concurrent queries, default 4) and {@code eval.gate}
 * (default false; the PR leg turns it on).</p>
 */
class RagEvalIT extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagEvalIT.class);

    private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration GROUNDEDNESS_TIMEOUT = Duration.ofMinutes(3);
    private static final Path REPORT_DIRECTORY = Path.of("target", "eval");
    private static final String INGESTION_SUCCESS = "INGESTION_SUCCESS";
    private static final String UPLOAD_FAILED = "UPLOAD_FAILED";
    private static final String ERROR = "ERROR";
    private static final int LOGGED_ANSWER_CHARS = 600;

    @Test
    @DisplayName("Eval: ingest the golden corpus, run the dataset items, write the report")
    void runEval() throws Exception {
        final EvalDataset dataset = EvalDataset.loadDefault();
        final List<EvalItem> items = selectItems(dataset);
        final int runs = Integer.getInteger("eval.runs", 1);
        final boolean gateEnabled = Boolean.getBoolean("eval.gate");
        // Loaded before ingestion even when the gate is off, so a stale register entry fails fast.
        final EvalGate gate = new EvalGate(KnownIssues.load(dataset));
        LOGGER.info("Eval: {} item(s) x {} run(s), gate {}: {}", items.size(), runs, gateEnabled ? "on" : "off",
                items.stream().map(EvalItem::id).toList());

        final List<DocumentIngestion> ingestion = ingestCorpus(dataset);
        final List<ItemRun> itemRuns = new ArrayList<>();
        final boolean ingested = ingestion.stream().allMatch(d -> INGESTION_SUCCESS.equals(d.status()));
        if (ingested) {
            final ItemScorer scorer = new ItemScorer(dataset, ingestion.stream()
                    .collect(Collectors.toMap(DocumentIngestion::documentId, DocumentIngestion::file)));
            final List<Map.Entry<EvalItem, Integer>> planned = new ArrayList<>();
            for (int run = 1; run <= runs; run++) {
                for (final EvalItem item : items) {
                    planned.add(Map.entry(item, run));
                }
            }
            itemRuns.addAll(execute(scorer, planned));
            if (gateEnabled) {
                itemRuns.addAll(execute(scorer, reruns(gate, items, itemRuns)));
            }
        } else {
            // M1 fails the run anyway, and answers over a partial corpus would only mislead.
            LOGGER.error("Eval: the corpus did not fully ingest, skipping the queries: {}", ingestion);
        }

        EvalReport report = EvalReport.build(dataset, settings(runs, gateEnabled), ingestion, itemRuns);
        if (gateEnabled && ingested) {
            report = report.withGate(gate.decide(items, itemRuns));
        }
        final Path file = REPORT_DIRECTORY.resolve("eval-report.json");
        report.write(file);
        report.write(REPORT_DIRECTORY.resolve("eval-report-" + Instant.now().toString().replace(":", "") + ".json"));
        logSummary(report, file);
        if (report.gate() != null) {
            logGate(report.gate(), itemRuns);
        }

        assertTrue(report.summary().allDocumentsIngested(), "M1: every corpus document must reach INGESTION_SUCCESS: " + ingestion);
        if (report.gate() != null) {
            assertTrue(report.gate().passed(), gateFailureMessage(report.gate(), file));
        }
    }

    private List<ItemRun> execute(final ItemScorer scorer, final List<Map.Entry<EvalItem, Integer>> planned)
            throws InterruptedException, ExecutionException {
        final List<ItemRun> itemRuns = new ArrayList<>();
        if (planned.isEmpty()) {
            return itemRuns;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("eval.parallelism", 4));
        try {
            final List<Future<ItemRun>> futures = new ArrayList<>();
            for (final Map.Entry<EvalItem, Integer> run : planned) {
                futures.add(pool.submit(() -> runItem(scorer, run.getKey(), run.getValue())));
            }
            for (final Future<ItemRun> future : futures) {
                itemRuns.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return itemRuns;
    }

    /** The extra runs the 2-of-3 rule needs: for each item whose first run failed, up to {@link EvalGate#ATTEMPTS} runs. */
    private static List<Map.Entry<EvalItem, Integer>> reruns(final EvalGate gate, final List<EvalItem> items,
                                                             final List<ItemRun> itemRuns) {
        final List<Map.Entry<EvalItem, Integer>> planned = new ArrayList<>();
        for (final EvalItem item : items) {
            final List<ItemRun> runs = itemRuns.stream().filter(r -> r.itemId().equals(item.id())).toList();
            if (gate.needsRerun(item, runs)) {
                final int last = runs.stream().mapToInt(ItemRun::run).max().orElse(0);
                LOGGER.warn("Eval gate: {} failed its first run {}, re-running for the 2-of-{} decision", item.id(),
                        gate.outcome(item, runs.stream().filter(r -> r.run() == 1).findFirst().orElse(runs.get(0))).failures(),
                        EvalGate.ATTEMPTS);
                for (int run = last + 1; run <= EvalGate.ATTEMPTS; run++) {
                    planned.add(Map.entry(item, run));
                }
            }
        }
        return planned;
    }

    private static List<EvalItem> selectItems(final EvalDataset dataset) {
        final String ids = System.getProperty("eval.items", "");
        if (!ids.isBlank()) {
            return Arrays.stream(ids.split(",")).map(String::strip).map(dataset::item).toList();
        }
        final String tier = System.getProperty("eval.tier", "full");
        return dataset.items().stream().filter(i -> i.tiers().contains(tier)).toList();
    }

    /**
     * M1: uploads every corpus document with its dataset metadata, then waits for each to reach a
     * terminal ingestion status. A document that fails to upload is recorded as UPLOAD_FAILED and
     * not waited on, so the report is still written.
     */
    private static List<DocumentIngestion> ingestCorpus(final EvalDataset dataset) {
        final Instant started = Instant.now();
        final Map<String, String> referenceByFile = new LinkedHashMap<>();
        for (final DocumentSpec document : dataset.documents()) {
            final DocumentUploadRequest request = new DocumentUploadRequest()
                    .documentId(randomUUID().toString())
                    .documentName(document.file());
            document.metadata().forEach(m -> request.addMetadataFilterItem(new MetadataFilter(m.key(), m.value())));
            try {
                final FileStorageLocationReturnedSuccessfully location =
                        DocumentIngestionFlow.initiateUpload(spec(DOCUMENT_METADATA_CHECK_FUNCTION), request);
                BlobUtil.uploadBytes(location.getStorageUrl(), Files.readAllBytes(dataset.documentPath(document.file())));
                referenceByFile.put(document.file(), location.getDocumentReference());
            } catch (final IOException | RuntimeException | AssertionError e) {
                LOGGER.error("Eval: {} did not upload: {}", document.file(), e.toString());
                referenceByFile.put(document.file(), null);
            }
        }

        final List<DocumentIngestion> outcomes = new ArrayList<>();
        referenceByFile.forEach((file, reference) -> {
            String status = UPLOAD_FAILED;
            if (reference != null) {
                try {
                    status = DocumentIngestionFlow.awaitIngestionOutcome(spec(DOCUMENT_STATUS_CHECK_FUNCTION), reference);
                } catch (final TimeoutException | RuntimeException | AssertionError e) {
                    LOGGER.error("Eval: {} did not ingest: {}", file, e.getMessage());
                    status = "NOT_INGESTED";
                }
            }
            if (!INGESTION_SUCCESS.equals(status)) {
                LOGGER.error("Eval: {} ended as {}", file, status);
            }
            outcomes.add(new DocumentIngestion(file, reference, status,
                    Duration.between(started, Instant.now()).toMillis() / 1000.0));
        });
        return outcomes;
    }

    /**
     * Runs one item once. Any failure inside the run (a rejected request, a contract mismatch, a
     * scoring error) is recorded on the run as an error, so one failed query does not lose the report.
     */
    private ItemRun runItem(final ItemScorer scorer, final EvalItem item, final int run) {
        final Instant started = Instant.now();
        String transactionId = null;
        try {
            transactionId = submitQuery(item);
            final AnswerResult result = awaitAnswer(item, run, transactionId, started);
            final ItemScore score = scorer.score(item, result);
            final Double groundedness = AnswerGenerationStatus.ANSWER_GENERATED.toString().equals(result.status())
                    ? awaitGroundedness(transactionId) : null;
            LOGGER.info("Eval: {} run {} -> {} in {}s, recall={}, clean={}", item.id(), run, score.status(),
                    score.elapsedSeconds(), score.factRecall(), EvalReport.isClean(score));
            return new ItemRun(item.id(), run, transactionId, groundedness, score, result.llmResponse(), null);
        } catch (final RuntimeException | AssertionError e) {
            LOGGER.error("Eval: {} run {} failed: {}", item.id(), run, e.toString());
            final AnswerResult failed = new AnswerResult(ERROR, null, null, List.of(),
                    Duration.between(started, Instant.now()).toMillis() / 1000.0);
            return new ItemRun(item.id(), run, transactionId, null, scorer.score(item, failed), null, e.toString());
        }
    }

    private static String submitQuery(final EvalItem item) {
        final AnswerUserQueryRequest request = new AnswerUserQueryRequest()
                .userQuery(item.query())
                .queryPrompt(item.queryPrompt())
                .metadataFilter(item.metadataFilter().stream().map(m -> new MetadataFilter(m.key(), m.value())).toList());
        final Response accepted = postRequest(spec(ANSWER_RETRIEVAL_FUNCTION).body(request).contentType("application/json"),
                "/answer-user-query-async", r -> r.getStatusCode() == 202);
        return assertMatchesContract(accepted, UserQueryAnswerRequestAccepted.class).getTransactionId();
    }

    private static AnswerResult awaitAnswer(final EvalItem item, final int run, final String transactionId, final Instant started) {
        try {
            // The query parameter goes on the spec, not in the path: RestAssured re-adds a path
            // query string on every poll of the same spec, and a repeated parameter reads as false.
            final Response response = pollForResponse(spec(ANSWER_RETRIEVAL_FUNCTION).queryParam("withChunkedEntries", true),
                    RestOperation.GET, "/answer-user-query-async-status/" + transactionId,
                    r -> r.getStatusCode() == 200
                            && !AnswerGenerationStatus.ANSWER_GENERATION_PENDING.toString().equals(r.jsonPath().getString("status")),
                    ANSWER_TIMEOUT, RestPoller.LLM_POLL_INTERVAL);
            final UserQueryAnswerReturnedSuccessfullyAsynchronously answer =
                    assertMatchesContract(response, UserQueryAnswerReturnedSuccessfullyAsynchronously.class);
            return new AnswerResult(String.valueOf(answer.getStatus()), answer.getLlmResponse(), answer.getReason(),
                    chunks(answer.getDocumentChunks()), Duration.between(started, Instant.now()).toMillis() / 1000.0);
        } catch (final TimeoutException e) {
            LOGGER.error("Eval: {} run {} timed out: {}", item.id(), run, e.getMessage());
            return new AnswerResult("TIMEOUT", null, null, List.of(), ANSWER_TIMEOUT.toSeconds());
        }
    }

    private static List<RetrievedChunk> chunks(final List<DocumentChunk> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream().map(c -> new RetrievedChunk(c.getDocumentId(), c.getPageNumber(),
                c.getCustomMetadata() == null ? List.of()
                        : c.getCustomMetadata().stream().map(m -> new MetadataEntry(m.getKey(), m.getValue())).toList())).toList();
    }

    /** M11: the scorer merges its groundedness score onto the answer row; null if it does not arrive in time. */
    private static Double awaitGroundedness(final String transactionId) {
        final String partitionKey = harness().clientFilteringEnabled() ? harness().testClientId() : transactionId;
        final AtomicReference<Object> score = new AtomicReference<>();
        try {
            await().atMost(GROUNDEDNESS_TIMEOUT).pollInterval(Duration.ofSeconds(5)).until(() -> {
                score.set(TableUtil.getEntityProperty(harness().tableStorageAccountEndpoint(), harness().answerGenerationTable(),
                        partitionKey, transactionId, TC_RESPONSE_GROUNDEDNESS_SCORE));
                return score.get() != null;
            });
            return new BigDecimal(score.get().toString()).doubleValue();
        } catch (final ConditionTimeoutException e) {
            LOGGER.warn("Eval: no groundedness score for {} within {}", transactionId, GROUNDEDNESS_TIMEOUT);
            return null;
        }
    }

    /**
     * A fresh request specification per call: the harness's shared one is mutated by
     * {@code body(...)}, which is not safe across the concurrent queries.
     */
    private static RequestSpecification spec(final FunctionAppName function) {
        return harness().requestSpecification(function, harness().testClientId());
    }

    /** What this run used, so reports from different models or settings can be told apart. */
    private static Map<String, String> settings(final int runs, final boolean gateEnabled) {
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", System.getenv("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME"));
        settings.put("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", System.getenv("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME"));
        settings.put("LLM_CHAT_SERVICE_PROVIDER", System.getenv().getOrDefault("LLM_CHAT_SERVICE_PROVIDER", "openai"));
        settings.put("CLIENT_FILTERING_ENABLED", String.valueOf(harness().clientFilteringEnabled()));
        settings.putAll(ServiceTuningEnv.tuningEnvEntries());
        settings.put("responseGenerationSystemPromptSha256", sha256("response-generation-system-prompt.txt"));
        settings.put("runs", String.valueOf(runs));
        settings.put("gate", String.valueOf(gateEnabled));
        settings.put("items", System.getProperty("eval.items", ""));
        settings.put("tier", System.getProperty("eval.tier", "full"));
        return settings;
    }

    private static String sha256(final String resource) {
        try (InputStream in = RagEvalIT.class.getClassLoader().getResourceAsStream(resource)) {
            final byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final IOException | NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static void logSummary(final EvalReport report, final Path file) {
        final StringBuilder table = new StringBuilder(String.format("%n%-16s %5s %6s %7s %7s %5s %s%n",
                "item", "clean", "recall", "precis", "ground", "degr", "notes"));
        for (final EvalReport.ItemResult item : report.items()) {
            final EvalReport.ItemSummary s = item.summary();
            final Set<String> notes = new TreeSet<>();
            item.runs().forEach(r -> {
                if (r.error() != null) {
                    notes.add("error");
                }
                notes.addAll(r.score().missingFacts().stream().map(m -> "missing:" + m).toList());
                notes.addAll(r.score().forbiddenHits().stream().map(f -> "forbidden:" + f).toList());
                if (!r.score().refusalCorrect()) {
                    notes.add(r.score().refused() ? "refused" : "did-not-refuse");
                }
                if (Boolean.FALSE.equals(r.score().retrievalOk())) {
                    notes.add("retrieval:" + r.score().retrievedDocuments());
                }
            });
            table.append(String.format("%-16s %2d/%-2d %6s %7s %7s %5d %s%n", item.id(), s.cleanRuns(), s.runs(),
                    s.meanFactRecall(), s.meanCitationPrecision(), s.meanGroundedness(), s.citationDegradedRuns(), notes));
        }
        LOGGER.info("Eval summary ({}): {}{}", file.toAbsolutePath(), report.summary(), table);
    }

    /**
     * The gate outcome in the build log: every failed item with each run's failures, answer, guard
     * reason and retrieved pages, so a failure can be diagnosed without the report file; then the
     * known issues and how often they showed.
     */
    private static void logGate(final EvalGate.Result result, final List<ItemRun> itemRuns) {
        for (final EvalGate.ItemVerdict verdict : result.failedItems()) {
            final StringBuilder block = new StringBuilder(String.format("%nEVAL GATE FAILED: %s (%d of %d runs passed, needs %d of %d)%n",
                    verdict.itemId(), verdict.runs().stream().filter(EvalGate.RunOutcome::passed).count(), verdict.runs().size(),
                    EvalGate.PASSES_NEEDED, EvalGate.ATTEMPTS));
            for (final EvalGate.RunOutcome outcome : verdict.runs()) {
                final ItemRun run = itemRuns.stream()
                        .filter(r -> r.itemId().equals(verdict.itemId()) && r.run() == outcome.run()).findFirst().orElseThrow();
                block.append(String.format("  run %d: %s%n", outcome.run(), outcome.passed() ? "passed" : outcome.failures()));
                if (!outcome.passed()) {
                    block.append(String.format("    transactionId: %s%n    retrieved: %s%n    reason: %s%n    answer: %s%n",
                            run.transactionId(), run.score().retrievedPages(), run.score().reason(), abbreviate(run.answer())));
                }
            }
            LOGGER.error("{}", block);
        }
        result.items().stream().filter(EvalGate.ItemVerdict::passed).filter(v -> v.runs().size() > 1)
                .forEach(v -> LOGGER.warn("Eval gate: {} passed on re-run: {}", v.itemId(),
                        v.runs().stream().map(o -> o.run() + "=" + (o.passed() ? "pass" : o.failures())).toList()));
        for (final KnownIssues.KnownIssue issue : result.knownIssues()) {
            final List<EvalGate.RunOutcome> runs = result.items().stream().filter(v -> v.itemId().equals(issue.item()))
                    .flatMap(v -> v.runs().stream()).toList();
            final long seen = runs.stream().filter(o -> o.knownIssuesSeen().contains(issue.check())).count();
            LOGGER.info("Eval gate: known issue {} {} ({}) seen in {} of {} run(s), not gated{}", issue.item(), issue.check(),
                    issue.jira(), seen, runs.size(), seen == 0 ? "; if it stays fixed, remove it from eval/known-issues.json" : "");
        }
        LOGGER.info("Eval gate: {} ({} of {} item(s) passed)", result.passed() ? "PASSED" : "FAILED",
                result.items().stream().filter(EvalGate.ItemVerdict::passed).count(), result.items().size());
    }

    private static String gateFailureMessage(final EvalGate.Result result, final Path file) {
        return "Eval gate failed for " + result.failedItems().stream()
                .map(v -> v.itemId() + " " + v.runs().stream().filter(o -> !o.passed()).map(EvalGate.RunOutcome::failures).toList())
                .collect(Collectors.joining("; "))
                + ". See the EVAL GATE FAILED block in the log and " + file.toAbsolutePath()
                + ". A known failure goes in eval/known-issues.json with its Jira; -Deval.gate=false makes the leg report-only.";
    }

    private static String abbreviate(final String answer) {
        if (answer == null) {
            return null;
        }
        final String flat = answer.replaceAll("\\s+", " ").strip();
        return flat.length() <= LOGGED_ANSWER_CHARS ? flat : flat.substring(0, LOGGED_ANSWER_CHARS) + "…";
    }
}
