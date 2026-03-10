package uk.gov.moj.cp.orchestrator;

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
import static uk.gov.moj.cp.orchestrator.util.QueueUtil.deleteQueue;
import static uk.gov.moj.cp.orchestrator.util.QueueUtil.ensureQueueExists;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.deleteTable;
import static uk.gov.moj.cp.orchestrator.util.TableUtil.ensureTableExists;

import uk.gov.moj.cp.orchestrator.util.FunctionHostManager;
import uk.gov.moj.cp.orchestrator.util.QueueUtil;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.azure.storage.queue.QueueClient;
import io.restassured.specification.RequestSpecification;
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

    private static final String RESPONSE_GENERATION_SYSTEM_PROMPT = "You are an expert Legal Advisor.\\nWho goes through the complete case document before responding and responds with every single detail to answer user's query.\\nUnderstand any kind of user query and respond accordingly\\\\\\nRespond purely based on the provided legal documents within <RETRIEVED_DOCUMENTS></RETRIEVED_DOCUMENTS> tags.\\\\\\n\\n**Instructions:**\\n1.  **Strictly adhere to the provided documents:** Answer the user's query *only* using information found within the {Retrieved Documents}\\\\\\n2.  **Provide Source for all factual statements:** For every factual statement you make you should include the citation\\n3.  **CRITICAL: Single Placeholder Rule (One Statement = One Citation ID per documentId):** For any single factual statement, regardless of how many pages within the same document, or fragments it draws upon, you **MUST** use only **ONE sequential numerical placeholder** (e.g., [1]). \\n    Immediately place this single placeholder after the factual statement. **NEVER** use multiple placeholders next to each other for the same document (e.g., [3][4] is **FORBIDDEN** for same documentId).\\n4.  **JSON Source Aggregation:**\\n    * If a single factual statement (linked to one placeholder, e.g., [1]) is supported by sources from **multiple documents** or **multiple page ranges**, you **MUST** include all necessary sources in the **sme objects** in the final JSON array.\\n5.  **JSON Page Formatting (For Single-Document Sources):** When a single source (defined by `documentId`) requires multiple pages:\\n    * **`individualPageNumbers`:** List all cited page numbers, comma-separated (e.g., \\\"17,18,19,20,21\\\").\\n    * **`pageNumbers`:** Compress consecutive page numbers using a hyphen, followed by non-consecutive pages (e.g., \\\"17-19,20,21\\\" or \\\"10-12,14,20\\\").\\n6.  **Guardrail Against Placeholder Generation:** To ensure compliance and prevent misinterpretation, you **MUST NOT** use numbers enclosed in square brackets (e.g., [1], [2], [3], etc.) for any purpose other than the mandatory source citation described in Instruction 3. If you need to list or enumerate items in the text, use parentheses (e.g., (1), (2), (3)), Roman numerals (e.g., (i), (ii)), or standard bullet points.\\n7.  **CRITICAL HEADING HIERARCHY:** For accessibility compliance (DAC/NFT level), you MUST follow proper heading structure:\\n    - NEVER use h1 (#) headings in your response as the page already has an h1\\n    - Use h2 (##) for main question titles and section headings\\n    - Use h3 (###) for subheadings\\n    - Use h4 (####) for sub-subheadings, and so on in descending order\\n    - This is mandatory for accessibility compliance\\n8.  **Data Output (JSON Array):** Immediately after your complete answer text, you MUST generate a single, minified JSON array containing all citation details. This data array must be wrapped in the exact literal tags: `<FACT_MAP_JSON>` and `</FACT_MAP_JSON>`.\\n9.  **JSON Schema:** Each object in the array must include the following keys:\\n    - \\\"citationId\\\": The sequential number used in the answer placeholder (e.g., 1, 2, 3).\\n    - \\\"documentFilename\\\": The filename of the source document.\\n    - \\\"pageNumbers\\\": The page numbers string with consecutive sequential page numbers hyphenated (e.g., \\\"10-12,14,20\\\").\\n    - \\\"individualPageNumbers\\\": The page numbers string (e.g., \\\"10,11,12,14,20\\\").\\n    - \\\"documentId\\\": The documentId GUID.\\nProvide the answer in a well written professional format.\\nAt the end of response, do not ask user for a follow up query.";

    private static final String TEST_RANDOM_KEY = randomAlphanumeric(10).toLowerCase();

    protected static final String DOCUMENT_LANDING_FOLDER = "test-documents-" + TEST_RANDOM_KEY;
    protected static final String DOCUMENT_LANDING_FOLDER_NEW = "test-documents-new-" + TEST_RANDOM_KEY;

    protected static final String LLM_EVAL_PAYLOADS_FOLDER = "test-llm-eval-payloads-" + TEST_RANDOM_KEY;
    protected static final String LLM_INPUT_CHUNKS_FOLDER = "test-llm-input-chunks-" + TEST_RANDOM_KEY;

    protected static final String DOCUMENT_STATUS_OUTCOME_TABLE = "testoutcometable" + TEST_RANDOM_KEY;
    protected static final String ANSWER_GENERATION_TABLE = "testanswergeneration" + TEST_RANDOM_KEY;

    protected static final String DOCUMENT_INGESTION_QUEUE = "test-ingestion-queue-" + TEST_RANDOM_KEY;
    protected static final String SCORING_QUEUE = "test-scoring-queue-" + TEST_RANDOM_KEY;
    protected static final String ANSWER_GENERATION_QUEUE = "test-answer-generation-" + TEST_RANDOM_KEY;

    protected static final String STORAGE_ACCOUNT_NAME = getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName");

    protected static final String BLOB_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.blob.core.windows.net/", STORAGE_ACCOUNT_NAME);
    protected static final String TABLE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.table.core.windows.net/", STORAGE_ACCOUNT_NAME);
    protected static final String QUEUE_STORAGE_ACCOUNT_ENDPOINT = String.format("https://%s.queue.core.windows.net/", STORAGE_ACCOUNT_NAME);

    static Map<FunctionAppName, Pair<FunctionHostManager, RequestSpecification>> FUNCTION_CONFIG_MAP;

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
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_LANDING_FOLDER_NEW);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_EVAL_PAYLOADS_FOLDER);
        ensureContainerExists(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_INPUT_CHUNKS_FOLDER);

        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_INGESTION_QUEUE);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, SCORING_QUEUE);
        ensureQueueExists(QUEUE_STORAGE_ACCOUNT_ENDPOINT, ANSWER_GENERATION_QUEUE);

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

    static @NotNull Map<String, String> setupEnvVarMap() {
        return Map.ofEntries(
                Map.entry("FUNCTIONS_WORKER_RUNTIME", "java"),
                Map.entry("FUNCTIONS_EXTENSION_VERSION", "~4"),

                Map.entry("AzureWebJobsSecretStorageType", "Files"),
                Map.entry("AzureWebJobsStorage", getRequiredEnv("AzureWebJobsStorage")),

                Map.entry("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName", STORAGE_ACCOUNT_NAME),
                Map.entry("AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT", BLOB_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT", TABLE_STORAGE_ACCOUNT_ENDPOINT),
                Map.entry("AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT", QUEUE_STORAGE_ACCOUNT_ENDPOINT),

                Map.entry("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME", DOCUMENT_STATUS_OUTCOME_TABLE),
                Map.entry("STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION", ANSWER_GENERATION_TABLE),

                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME", DOCUMENT_LANDING_FOLDER),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD", DOCUMENT_LANDING_FOLDER_NEW),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS", LLM_EVAL_PAYLOADS_FOLDER),
                Map.entry("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS", LLM_INPUT_CHUNKS_FOLDER),

                Map.entry("STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION", DOCUMENT_INGESTION_QUEUE),
                Map.entry("STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING", SCORING_QUEUE),
                Map.entry("STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION", ANSWER_GENERATION_QUEUE),

                Map.entry("RESPONSE_GENERATION_SYSTEM_PROMPT", RESPONSE_GENERATION_SYSTEM_PROMPT),

                Map.entry("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT", getRequiredEnv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT")),

                Map.entry("AZURE_SEARCH_SERVICE_ENDPOINT", getRequiredEnv("AZURE_SEARCH_SERVICE_ENDPOINT")),
                Map.entry("AZURE_SEARCH_SERVICE_INDEX_NAME", getRequiredEnv("AZURE_SEARCH_SERVICE_INDEX_NAME")),

                Map.entry("AZURE_EMBEDDING_SERVICE_ENDPOINT", getRequiredEnv("AZURE_EMBEDDING_SERVICE_ENDPOINT")),
                Map.entry("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME", getRequiredEnv("AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME")),

                Map.entry("AZURE_OPENAI_ENDPOINT", getRequiredEnv("AZURE_OPENAI_ENDPOINT")),
                Map.entry("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME", getRequiredEnv("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME")),

                Map.entry("AZURE_JUDGE_OPENAI_ENDPOINT", getRequiredEnv("AZURE_JUDGE_OPENAI_ENDPOINT")),
                Map.entry("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME", getRequiredEnv("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME")),

                Map.entry("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING", getRequiredEnv("RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING"))
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
        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_LANDING_FOLDER_NEW);
        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_EVAL_PAYLOADS_FOLDER);
        deleteContainer(BLOB_STORAGE_ACCOUNT_ENDPOINT, LLM_INPUT_CHUNKS_FOLDER);

        deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, SCORING_QUEUE);
        deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_INGESTION_QUEUE);
        deleteQueue(QUEUE_STORAGE_ACCOUNT_ENDPOINT, ANSWER_GENERATION_QUEUE);

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