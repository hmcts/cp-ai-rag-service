package uk.gov.moj.cp.orchestrator;

import static io.restassured.RestAssured.given;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_SCORING_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_INGESTION_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.deleteContainer;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.ensureContainerExists;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.deleteTable;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.ensureTableExists;

import uk.gov.moj.cp.orchestrator.util.FunctionHostManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for integration tests that need a local Azure Function host running. Manages startup,
 * base URI configuration, and shutdown.
 */
public abstract class FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionTestBase.class);

    private static final String ANSWER_RETRIEVAL_FUNCTION_DIRECTORY = "../ai-document-answer-retrieval-function/target/azure-functions/fa-ste-ai-document-answer-retrieval";
    private static final String ANSWER_SCORING_FUNCTION_DIRECTORY = "../ai-document-answer-scoring-function/target/azure-functions/fa-ste-ai-document-answer-scoring";
    private static final String DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY = "../ai-document-status-check-function/target/azure-functions/fa-ste-ai-document-status-check";
    private static final String DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY = "../ai-document-metadata-check-function/target/azure-functions/fa-ste-ai-document-metadata-check";
    private static final String DOCUMENT_INGESTION_FUNCTION_DIRECTORY = "../ai-document-ingestion-function/target/azure-functions/fa-ste-ai-document-ingestion";

    private static final String TEST_RANDOM_KEY = randomAlphanumeric(10).toLowerCase();
    protected static final String DOCUMENT_LANDING_FOLDER = "test-documents-folder-" + TEST_RANDOM_KEY;
    protected static final String LLM_EVAL_PAYLOADS_FOLDER = "test-llm-eval-payloads-folder-" + TEST_RANDOM_KEY;
    protected static final String LLM_INPUT_CHUNKS_FOLDER = "test-llm-input-chunks-folder-" + TEST_RANDOM_KEY;
    protected static final String DOCUMENT_STATUS_OUTCOME_TABLE = "testoutcometable" + TEST_RANDOM_KEY;
    protected static final String ANSWER_GENERATION_TABLE = "testanswergeneration" + TEST_RANDOM_KEY;
    protected static final String DOCUMENT_INGESTION_QUEUE = "test-ingestion-queue" + TEST_RANDOM_KEY;
    protected static final String SCORING_QUEUE = "test-scoring-queue" + TEST_RANDOM_KEY;
    protected static final String STORAGE_ACCOUNT_NAME = getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName");

    protected static final String BLOB_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.blob.core.windows.net/", STORAGE_ACCOUNT_NAME);
    protected static final String TABLE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.table.core.windows.net/", STORAGE_ACCOUNT_NAME);
    protected static final String QUEUE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.queue.core.windows.net/", STORAGE_ACCOUNT_NAME);

    private static Map<FunctionAppName, Pair<FunctionHostManager, RequestSpecification>> FUNCTION_CONFIG_MAP;

    private static int getAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    public static void setupFunctionHost() throws IOException {
        final int answerRetrievalFunctionPort = getAvailablePort();
        final int answerScoringFunctionPort = getAvailablePort();
        final int documentIngestionFunctionPort = getAvailablePort();
        final int documentMetadataCheckFunctionPort = getAvailablePort();
        final int documentStatusCheckFunctionPort = getAvailablePort();

        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_LANDING_FOLDER);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_EVAL_PAYLOADS_FOLDER);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_INPUT_CHUNKS_FOLDER);
        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_STATUS_OUTCOME_TABLE);
        ensureTableExists(TABLE_STORAGE_ACCOUNT_ENDPOINT, ANSWER_GENERATION_TABLE);

        FUNCTION_CONFIG_MAP = Map.of(
                DOCUMENT_METADATA_CHECK_FUNCTION, getFunctionConfig(documentMetadataCheckFunctionPort, DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_STATUS_CHECK_FUNCTION, getFunctionConfig(documentStatusCheckFunctionPort, DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_INGESTION_FUNCTION, getFunctionConfig(documentIngestionFunctionPort, DOCUMENT_INGESTION_FUNCTION_DIRECTORY),
                ANSWER_RETRIEVAL_FUNCTION, getFunctionConfig(answerRetrievalFunctionPort, ANSWER_RETRIEVAL_FUNCTION_DIRECTORY),
                ANSWER_SCORING_FUNCTION, getFunctionConfig(answerScoringFunctionPort, ANSWER_SCORING_FUNCTION_DIRECTORY)
        );

        FUNCTION_CONFIG_MAP.values().forEach(fcm -> {
            try {
                fcm.getLeft().start(setupEnvVarMap());
            } catch (IOException | InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        });


    }

    private static @NotNull Map<String, String> setupEnvVarMap() {
        return Map.ofEntries(
                Map.entry("AzureWebJobsStorage", getRequiredEnv("AzureWebJobsStorage")),
                Map.entry("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName", STORAGE_ACCOUNT_NAME),
                Map.entry("AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT", BLOB_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT", TABLE_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT", QUEUE_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME", DOCUMENT_STATUS_OUTCOME_TABLE),
                Map.entry("STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION", ANSWER_GENERATION_TABLE),
                Map.entry("STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION", DOCUMENT_INGESTION_QUEUE),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME", DOCUMENT_LANDING_FOLDER),
                Map.entry("STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING", SCORING_QUEUE),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS", LLM_EVAL_PAYLOADS_FOLDER),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS", LLM_INPUT_CHUNKS_FOLDER),

                Map.entry("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT", "https://document-intelligence-ste-ai-qwrw.cognitiveservices.azure.com/"),

                Map.entry("AZURE_SEARCH_SERVICE_ENDPOINT", "https://search-service-ste-ai-01.search.windows.net"),
                Map.entry("AZURE_SEARCH_SERVICE_INDEX_NAME", "ai-rag-service-index"),

                Map.entry("AZURE_EMBEDDING_SERVICE_ENDPOINT", "https://open-ai-ste-c3vx.openai.azure.com"),
                Map.entry("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME", "text-embedding-3-large"),

                Map.entry("AZURE_OPENAI_ENDPOINT", "https://open-ai-ste-c3vx.cognitiveservices.azure.com"),
                Map.entry("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", "gpt-4o-response-generation"),

                Map.entry("AZURE_JUDGE_OPENAI_ENDPOINT", "https://open-ai-ste-c3vx.openai.azure.com"),
                Map.entry("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", "gpt-4o-judge"),

                Map.entry("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING", "InstrumentationKey=a1825998-dce1-4bd8-b74f-29ad1a4c382c;IngestionEndpoint=https://uksouth-1.in.applicationinsights.azure.com/;LiveEndpoint=https://uksouth.livediagnostics.monitor.azure.com/;ApplicationId=fb1f6c66-6296-4611-99e3-ee9cfe6ca5b8")
        );
    }


    /**
     * Executes once after all tests in the class have finished. Ensures the local Azure Function
     * host process is cleanly stopped.
     */
    @AfterAll
    public static void tearDownFunctionHost() {
        FUNCTION_CONFIG_MAP.values().forEach(pair -> {
            pair.getLeft().stop();
        });

        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_LANDING_FOLDER);
        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_EVAL_PAYLOADS_FOLDER);
        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_INPUT_CHUNKS_FOLDER);

        deleteTable(TABLE_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_STATUS_OUTCOME_TABLE);
        deleteTable(TABLE_STORAGE_ACCOUNT_ENDPOINT, ANSWER_GENERATION_TABLE);

    }

    private static Pair<FunctionHostManager, RequestSpecification> getFunctionConfig(final Integer port, final String appDirectory) {
        return Pair.of(new FunctionHostManager(appDirectory, port), given()
                .baseUri("http://localhost")
                .port(port)
                .basePath("/api"));
    }

    protected RequestSpecification getRequestSpecification(final FunctionAppName functionAppName) {
        return FUNCTION_CONFIG_MAP.get(functionAppName).getRight();
    }
}