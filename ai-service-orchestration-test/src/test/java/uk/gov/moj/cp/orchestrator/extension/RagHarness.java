package uk.gov.moj.cp.orchestrator.extension;

import static io.restassured.RestAssured.given;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_SCORING_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_INGESTION_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.deleteContainer;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.ensureContainerExists;
import static uk.gov.moj.cp.orchestrator.util.IndexUtil.createIndexFromSchema;
import static uk.gov.moj.cp.orchestrator.util.IndexUtil.deleteIndex;
import static uk.gov.moj.cp.orchestrator.util.QueueUtil.deleteQueue;
import static uk.gov.moj.cp.orchestrator.util.QueueUtil.ensureQueueExists;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.deleteTable;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.ensureTableExists;

import uk.gov.moj.cp.orchestrator.FunctionAppName;
import uk.gov.moj.cp.orchestrator.util.FunctionHostManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The expensive cross-class fixture: five local Azure Function hosts plus the per-run blob
 * containers, queues, tables and AI Search indexes (one per schema version) they use. Created once per test run by
 * {@link RagHarnessExtension} (the first test class to start pays for it, later classes reuse
 * it) and closed automatically by JUnit when the root extension context ends — after ALL tests,
 * regardless of failures — via {@link ExtensionContext.Store.CloseableResource}.
 *
 * <p>Sharing queues/tables across test classes is safe because classes run sequentially in one
 * JVM (failsafe default: single reused fork) and all row/message keys are per-test random UUIDs.
 * Do not enable JUnit parallel class execution: OrchestrationIT's idempotency check waits for
 * the shared answer-generation queue to drain, which assumes no concurrent traffic.</p>
 */
public final class RagHarness implements ExtensionContext.Store.CloseableResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagHarness.class);

    /** Header carrying the caller's client identity (matches the {@code CLIENT_IDENTITY_HEADER} default). */
    static final String CLIENT_IDENTITY_HEADER = "X-Client-Id";

    /**
     * Enforcement mode for this run. Read once from the JVM environment — each failsafe execution
     * (leg) injects its own value, and the harness forwards it verbatim to every function host, so
     * the harness, the hosts, and the tests always agree on the mode. Defaults to enforcement-on
     * for ad-hoc runs outside Maven. The legacy (flag-off) leg exists until cut-over; the
     * enforcement-on leg is the target state.
     */
    private static final boolean CLIENT_FILTERING_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("CLIENT_FILTERING_ENABLED", "true"));

    /**
     * The suite's default (client A) identity, applied to every request built by the header-bearing
     * request specification. When the hosts run with client filtering ON this identity is enforced:
     * rows, blobs, search results and telemetry for the default requests are all scoped to it; when
     * OFF the hosts ignore the header entirely and the flow is the legacy single-client one. A
     * second identity ({@link #SECOND_TEST_CLIENT_ID}) drives the cross-client isolation checks.
     */
    static final String TEST_CLIENT_ID = "00000000-0000-0000-0000-0000000000aa";

    /**
     * A second, distinct client identity used by the isolation checks to prove that data ingested or
     * queried under one client is invisible to another (cross-client 404, no query leakage, and the
     * same {@code documentId} coexisting across clients).
     */
    static final String SECOND_TEST_CLIENT_ID = "00000000-0000-0000-0000-0000000000bb";

    private static final String ANSWER_RETRIEVAL_FUNCTION_DIRECTORY = "../ai-document-answer-retrieval-function/target/azure-functions/fa-ste-ai-document-answer-retrieval";
    private static final String ANSWER_SCORING_FUNCTION_DIRECTORY = "../ai-document-answer-scoring-function/target/azure-functions/fa-ste-ai-document-answer-scoring";
    private static final String DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY = "../ai-document-status-check-function/target/azure-functions/fa-ste-ai-document-status-check";
    private static final String DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY = "../ai-document-metadata-check-function/target/azure-functions/fa-ste-ai-document-metadata-check";
    private static final String DOCUMENT_INGESTION_FUNCTION_DIRECTORY = "../ai-document-ingestion-function/target/azure-functions/fa-ste-ai-document-ingestion";

    /**
     * The prompt resource is plain readable text; the deployed Azure app setting stores it as a
     * single line with literal {@code \n} and {@code \"} escape sequences, so the same encoding
     * is applied here before the value is handed to the function hosts.
     */
    private static final String RESPONSE_GENERATION_SYSTEM_PROMPT =
            toSingleLineEscapedPrompt(readResource("response-generation-system-prompt.txt"));

    private static final String STORAGE_ACCOUNT_NAME = getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName");

    private static final String SEARCH_SERVICE_ENDPOINT = getRequiredEnv("AZURE_SEARCH_SERVICE_ENDPOINT");

    /** Schema resource from the shared artefacts jar — the rebuilt (v2) index shape. */
    private static final String V2_SCHEMA_RESOURCE = "/vector-db-index-schema-v2.json";

    private static final String BLOB_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.blob.core.windows.net/", STORAGE_ACCOUNT_NAME);
    private static final String TABLE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.table.core.windows.net/", STORAGE_ACCOUNT_NAME);
    private static final String QUEUE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.queue.core.windows.net/", STORAGE_ACCOUNT_NAME);

    private final String documentLandingFolder;
    private final String llmEvalPayloadsFolder;
    private final String llmInputChunksFolder;
    private final String documentStatusOutcomeTable;
    private final String answerGenerationTable;
    private final String documentIngestionQueue;
    private final String scoringQueue;
    private final String answerGenerationQueue;
    private final String searchIndexV2;

    private final Map<FunctionAppName, Pair<FunctionHostManager, RequestSpecification>> functionConfigMap;
    private final Map<FunctionAppName, Integer> functionPorts;

    RagHarness() {
        LOGGER.info("RAG harness: creating Azure test resources and starting function hosts (once per test run)");

        final String testRandomKey = randomAlphanumeric(10).toLowerCase();
        documentLandingFolder = "test-documents-new-" + testRandomKey;
        llmEvalPayloadsFolder = "test-llm-eval-payloads-" + testRandomKey;
        llmInputChunksFolder = "test-llm-input-chunks-" + testRandomKey;
        documentStatusOutcomeTable = "testoutcometable" + testRandomKey;
        answerGenerationTable = "testanswergeneration" + testRandomKey;
        documentIngestionQueue = "test-ingestion-queue-" + testRandomKey;
        scoringQueue = "test-scoring-queue-" + testRandomKey;
        answerGenerationQueue = "test-answer-generation-" + testRandomKey;
        searchIndexV2 = "test-index-v2-" + testRandomKey;

        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, documentLandingFolder);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmEvalPayloadsFolder);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmInputChunksFolder);

        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, documentIngestionQueue);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, scoringQueue);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationQueue);

        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, documentStatusOutcomeTable);
        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationTable);

        // A per-run, initially-empty v2-schema index, so tests exercise their own ingestion
        // end-to-end instead of reusing a shared pre-existing index. Only v2 is provisioned:
        // no test consumes a v1-shaped index, and each index is an orphan-risk while the
        // runner identity lacks delete rights on the search service. Recreate a v1 index here
        // if a migration-shaped test ever needs one.
        createIndexFromSchema(SEARCH_SERVICE_ENDPOINT, searchIndexV2, V2_SCHEMA_RESOURCE);

        // Ports are allocated up front and retained so per-client request specifications (default
        // client, a second client, or no client header at all) can be built on demand against the
        // same hosts.
        functionPorts = Map.of(
                DOCUMENT_METADATA_CHECK_FUNCTION, getAvailablePort(),
                DOCUMENT_STATUS_CHECK_FUNCTION, getAvailablePort(),
                DOCUMENT_INGESTION_FUNCTION, getAvailablePort(),
                ANSWER_RETRIEVAL_FUNCTION, getAvailablePort(),
                ANSWER_SCORING_FUNCTION, getAvailablePort()
        );

        functionConfigMap = Map.of(
                DOCUMENT_METADATA_CHECK_FUNCTION, getFunctionConfig(functionPorts.get(DOCUMENT_METADATA_CHECK_FUNCTION), DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_STATUS_CHECK_FUNCTION, getFunctionConfig(functionPorts.get(DOCUMENT_STATUS_CHECK_FUNCTION), DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_INGESTION_FUNCTION, getFunctionConfig(functionPorts.get(DOCUMENT_INGESTION_FUNCTION), DOCUMENT_INGESTION_FUNCTION_DIRECTORY),
                ANSWER_RETRIEVAL_FUNCTION, getFunctionConfig(functionPorts.get(ANSWER_RETRIEVAL_FUNCTION), ANSWER_RETRIEVAL_FUNCTION_DIRECTORY),
                ANSWER_SCORING_FUNCTION, getFunctionConfig(functionPorts.get(ANSWER_SCORING_FUNCTION), ANSWER_SCORING_FUNCTION_DIRECTORY)
        );

        // Launch every host first, then await readiness — the hosts boot concurrently instead of
        // paying five sequential startups. close() stops whatever launched if any of this throws
        // (JUnit closes the stored resource even when construction of dependent state fails later).
        final Map<String, String> envVarMap = setupEnvVarMap();
        for (final Pair<FunctionHostManager, RequestSpecification> fcm : functionConfigMap.values()) {
            try {
                fcm.getLeft().launch(envVarMap);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to launch function host", e);
            }
        }
        for (final Pair<FunctionHostManager, RequestSpecification> fcm : functionConfigMap.values()) {
            try {
                fcm.getLeft().awaitReady();
            } catch (InterruptedException | TimeoutException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                stopAllHosts(new ArrayList<>());
                throw new IllegalStateException("Function host failed to become ready", e);
            }
        }
    }

    public RequestSpecification requestSpecification(final FunctionAppName functionAppName) {
        return functionConfigMap.get(functionAppName).getRight();
    }

    /** Whether the function hosts of this run enforce client identity (see {@link #CLIENT_FILTERING_ENABLED}). */
    public boolean clientFilteringEnabled() {
        return CLIENT_FILTERING_ENABLED;
    }

    /** The default (client A) identity carried by {@link #requestSpecification(FunctionAppName)}. */
    public String testClientId() {
        return TEST_CLIENT_ID;
    }

    /** The second client identity used by the cross-client isolation checks. */
    public String secondTestClientId() {
        return SECOND_TEST_CLIENT_ID;
    }

    /**
     * A fresh request specification for the given host carrying the supplied client identity header,
     * for driving requests as a client other than the default one.
     */
    public RequestSpecification requestSpecification(final FunctionAppName functionAppName, final String clientId) {
        return baseRequestSpecification(functionAppName).header(CLIENT_IDENTITY_HEADER, clientId);
    }

    /**
     * A fresh request specification for the given host with no client identity header at all, for
     * asserting the enforcement rejection (401) when the header is absent.
     */
    public RequestSpecification requestSpecificationWithoutClientHeader(final FunctionAppName functionAppName) {
        return baseRequestSpecification(functionAppName);
    }

    private RequestSpecification baseRequestSpecification(final FunctionAppName functionAppName) {
        return given()
                .baseUri("http://localhost")
                .port(functionPorts.get(functionAppName))
                .basePath("/api");
    }

    public String queueStorageAccountEndpoint() {
        return QUEUE_STORAGE_ACCOUNT_ENDPOINT;
    }

    public String tableStorageAccountEndpoint() {
        return TABLE_STORAGE_ACCOUNT_ENDPOINT;
    }

    public String answerGenerationQueue() {
        return answerGenerationQueue;
    }

    public String answerGenerationTable() {
        return answerGenerationTable;
    }

    public String blobStorageAccountEndpoint() {
        return BLOB_STORAGE_ACCOUNT_ENDPOINT;
    }

    public String documentLandingFolder() {
        return documentLandingFolder;
    }

    public String documentIngestionQueue() {
        return documentIngestionQueue;
    }

    /** The per-run index built from the rebuilt (v2) schema — the one the function hosts point at. */
    public String searchIndexV2() {
        return searchIndexV2;
    }

    /**
     * Invoked by JUnit when the root extension context closes (after all tests). Every cleanup
     * step runs even if an earlier one throws, so a single failure cannot leak the remaining
     * hosts or Azure resources; failures are collected and rethrown at the end.
     */
    @Override
    public void close() {
        LOGGER.info("RAG harness: stopping function hosts and deleting Azure test resources");
        final List<RuntimeException> failures = new ArrayList<>();

        stopAllHosts(failures);

        runCleanupStep(failures, () -> deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, documentLandingFolder));
        runCleanupStep(failures, () -> deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmEvalPayloadsFolder));
        runCleanupStep(failures, () -> deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmInputChunksFolder));

        runCleanupStep(failures, () -> deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, scoringQueue));
        runCleanupStep(failures, () -> deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, documentIngestionQueue));
        runCleanupStep(failures, () -> deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationQueue));

        runCleanupStep(failures, () -> deleteTable(TABLE_STORAGE_ACCOUNT_ENDPOINT, documentStatusOutcomeTable));
        runCleanupStep(failures, () -> deleteTable(TABLE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationTable));

        // Best-effort: index deletion needs delete rights on the search service, which not every
        // runner's identity has yet. A missing grant must not fail an otherwise-green run — but the
        // orphaned index names are logged loudly so they can be cleaned up manually.
        runBestEffortCleanupStep(() -> deleteIndex(SEARCH_SERVICE_ENDPOINT, searchIndexV2), searchIndexV2);

        if (!failures.isEmpty()) {
            final RuntimeException teardownFailure = new RuntimeException(failures.size() + " teardown step(s) failed; see suppressed exceptions");
            failures.forEach(teardownFailure::addSuppressed);
            throw teardownFailure;
        }
    }

    private void stopAllHosts(final List<RuntimeException> failures) {
        functionConfigMap.values().forEach(pair -> runCleanupStep(failures, () -> pair.getLeft().stop()));
    }

    private static void runBestEffortCleanupStep(final Runnable step, final String resourceName) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOGGER.warn("ORPHANED TEST RESOURCE — could not delete '{}' (likely missing delete rights "
                    + "on the search service); remove it manually.", resourceName, e);
        }
    }

    private static void runCleanupStep(final List<RuntimeException> failures, final Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LOGGER.error("Teardown step failed", e);
            failures.add(e);
        }
    }

    private Map<String, String> setupEnvVarMap() {
        return Map.ofEntries(
                Map.entry("FUNCTIONS_WORKER_RUNTIME", "java"),
                Map.entry("FUNCTIONS_EXTENSION_VERSION", "~4"),

                Map.entry("AzureWebJobsSecretStorageType", "Files"),
                Map.entry("AzureWebJobsStorage", getRequiredEnv("AzureWebJobsStorage")),

                Map.entry("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName", STORAGE_ACCOUNT_NAME),
                Map.entry("AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT", BLOB_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT", TABLE_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT", QUEUE_STORAGE_ACCOUNT_ENDPOINT),

                Map.entry("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME", documentStatusOutcomeTable),
                Map.entry("STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION", answerGenerationTable),

                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD", documentLandingFolder),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS", llmEvalPayloadsFolder),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS", llmInputChunksFolder),

                Map.entry("STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION", documentIngestionQueue),
                Map.entry("STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING", scoringQueue),
                Map.entry("STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION", answerGenerationQueue),

                // Test hosts only: cut the queue-scan backoff (host.json ships 10s) so each queue
                // hop — ingestion, answer generation, scoring, and the blob trigger's internal
                // receipt scan — picks work up within ~2s. Production host.json is untouched.
                Map.entry("AzureFunctionsJobHost__extensions__queues__maxPollingInterval", "00:00:02"),

                Map.entry("RESPONSE_GENERATION_SYSTEM_PROMPT", RESPONSE_GENERATION_SYSTEM_PROMPT),
                Map.entry("CITATION_GUARD_MODE", "OFF"),

                // Client-identity enforcement ON: the HTTP functions require the identity header
                // (else 401), rows/blobs/search/telemetry are scoped to the resolved client, and
                // cross-client lookups resolve to 404. Safe per-run because the harness owns all of
                // its own indexes/tables/queues/blob folders.
                Map.entry("CLIENT_FILTERING_ENABLED", String.valueOf(CLIENT_FILTERING_ENABLED)),
                Map.entry("CLIENT_IDENTITY_HEADER", CLIENT_IDENTITY_HEADER),

                // 1 MiB (prod default 80): small enough for NegativePathIT to trip
                // FILE_SIZE_OVER_LIMIT with a 2 MiB blob, while the ~16 KB PDF fixtures pass
                Map.entry("MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB", "1"),

                Map.entry("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT", getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT")),

                Map.entry("AZURE_SEARCH_SERVICE_ENDPOINT", SEARCH_SERVICE_ENDPOINT),
                // The hosts point at the per-run v2 index (the future production shape); flag-off
                // code never references the client field, so behaviour matches a v1 index exactly.
                Map.entry("AZURE_SEARCH_SERVICE_INDEX_NAME", searchIndexV2),

                Map.entry("AZURE_EMBEDDING_SERVICE_ENDPOINT", getRequiredEnv("AZURE_EMBEDDING_SERVICE_ENDPOINT")),
                Map.entry("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME", getRequiredEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME")),

                Map.entry("AZURE_OPENAI_ENDPOINT", getRequiredEnv("AZURE_OPENAI_ENDPOINT")),
                Map.entry("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", getRequiredEnv("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME")),

                Map.entry("AZURE_JUDGE_OPENAI_ENDPOINT", getRequiredEnv("AZURE_JUDGE_OPENAI_ENDPOINT")),
                Map.entry("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", getRequiredEnv("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME")),

                Map.entry("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING", getRequiredEnv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING"))
        );
    }

    private static Pair<FunctionHostManager, RequestSpecification> getFunctionConfig(final int port, final String appDirectory) {
        return Pair.of(new FunctionHostManager(appDirectory, port), given()
                .baseUri("http://localhost")
                .port(port)
                .basePath("/api")
                .header(CLIENT_IDENTITY_HEADER, TEST_CLIENT_ID));
    }

    private static int getAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate a free port", e);
        }
    }

    private static String readResource(final String resourceName) {
        try (var in = RagHarness.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found on classpath: " + resourceName);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + resourceName, e);
        }
    }

    private static String toSingleLineEscapedPrompt(final String readablePrompt) {
        return readablePrompt
                .replace("\r\n", "\n")
                .stripTrailing()
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
