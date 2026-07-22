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
     * Deterministic client identity applied to every request the suite sends. The function hosts
     * run with client filtering off (default), so the resolver ignores this header and behaviour is
     * unchanged — the suite stays compatible with a flag-off environment. The enforcement-on matrix
     * is a later story.
     */
    static final String TEST_CLIENT_ID = "00000000-0000-0000-0000-0000000000aa";

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

    /** Schema resources from the shared artefacts jar — the live (v1) shape and the rebuilt (v2) shape. */
    private static final String V1_SCHEMA_RESOURCE = "/vector-db-index-schema.json";
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
    private final String searchIndexV1;
    private final String searchIndexV2;

    private final Map<FunctionAppName, Pair<FunctionHostManager, RequestSpecification>> functionConfigMap;

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
        searchIndexV1 = "test-index-v1-" + testRandomKey;
        searchIndexV2 = "test-index-v2-" + testRandomKey;

        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, documentLandingFolder);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmEvalPayloadsFolder);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, llmInputChunksFolder);

        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, documentIngestionQueue);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, scoringQueue);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationQueue);

        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, documentStatusOutcomeTable);
        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, answerGenerationTable);

        // Per-run, initially-empty indexes in both schema shapes, so tests exercise their own
        // ingestion end-to-end and can target either version explicitly, instead of reusing a
        // shared pre-existing index that only carries one version's schema.
        createIndexFromSchema(SEARCH_SERVICE_ENDPOINT, searchIndexV1, V1_SCHEMA_RESOURCE);
        createIndexFromSchema(SEARCH_SERVICE_ENDPOINT, searchIndexV2, V2_SCHEMA_RESOURCE);

        functionConfigMap = Map.of(
                DOCUMENT_METADATA_CHECK_FUNCTION, getFunctionConfig(getAvailablePort(), DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_STATUS_CHECK_FUNCTION, getFunctionConfig(getAvailablePort(), DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_INGESTION_FUNCTION, getFunctionConfig(getAvailablePort(), DOCUMENT_INGESTION_FUNCTION_DIRECTORY),
                ANSWER_RETRIEVAL_FUNCTION, getFunctionConfig(getAvailablePort(), ANSWER_RETRIEVAL_FUNCTION_DIRECTORY),
                ANSWER_SCORING_FUNCTION, getFunctionConfig(getAvailablePort(), ANSWER_SCORING_FUNCTION_DIRECTORY)
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

    /** The per-run index built from the live (v1) schema — empty unless a test targets it explicitly. */
    public String searchIndexV1() {
        return searchIndexV1;
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

        runCleanupStep(failures, () -> deleteIndex(SEARCH_SERVICE_ENDPOINT, searchIndexV1));
        runCleanupStep(failures, () -> deleteIndex(SEARCH_SERVICE_ENDPOINT, searchIndexV2));

        if (!failures.isEmpty()) {
            final RuntimeException teardownFailure = new RuntimeException(failures.size() + " teardown step(s) failed; see suppressed exceptions");
            failures.forEach(teardownFailure::addSuppressed);
            throw teardownFailure;
        }
    }

    private void stopAllHosts(final List<RuntimeException> failures) {
        functionConfigMap.values().forEach(pair -> runCleanupStep(failures, () -> pair.getLeft().stop()));
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

                Map.entry("RESPONSE_GENERATION_SYSTEM_PROMPT", RESPONSE_GENERATION_SYSTEM_PROMPT),
                Map.entry("CITATION_GUARD_MODE", "OFF"),

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
