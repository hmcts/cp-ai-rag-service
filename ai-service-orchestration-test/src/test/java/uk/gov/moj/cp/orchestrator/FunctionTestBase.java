package uk.gov.moj.cp.orchestrator;

import static io.restassured.RestAssured.given;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_INGESTION_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;

import uk.gov.moj.cp.orchestrator.util.AzuriteContainer;
import uk.gov.moj.cp.orchestrator.util.FunctionHostManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.tuple.Pair;
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

    private static Map<FunctionAppName, Pair<FunctionHostManager, RequestSpecification>> FUNCTION_CONFIG_MAP;

    protected static AzuriteContainer AZURITE_CONTAINER;

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

        AZURITE_CONTAINER = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.33.0");
        AZURITE_CONTAINER.start();
        AZURITE_CONTAINER.ensureContainerExists("azure-webjobs-secrets");
        AZURITE_CONTAINER.ensureContainerExists("azure-webjobs-hosts");
        AZURITE_CONTAINER.ensureContainerExists("documents");
        AZURITE_CONTAINER.ensureTableExists("documentingestiontable");

        FUNCTION_CONFIG_MAP = Map.of(
                DOCUMENT_METADATA_CHECK_FUNCTION, getFunctionConfig(documentMetadataCheckFunctionPort, DOCUMENT_METADATA_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_STATUS_CHECK_FUNCTION, getFunctionConfig(documentStatusCheckFunctionPort, DOCUMENT_STATUS_CHECK_FUNCTION_DIRECTORY),
                DOCUMENT_INGESTION_FUNCTION, getFunctionConfig(documentIngestionFunctionPort, DOCUMENT_INGESTION_FUNCTION_DIRECTORY)
//                ANSWER_RETRIEVAL_FUNCTION, getFunctionConfig(answerRetrievalFunctionPort, ANSWER_RETRIEVAL_FUNCTION_DIRECTORY),
//                ANSWER_SCORING_FUNCTION, getFunctionConfig(answerScoringFunctionPort, ANSWER_SCORING_FUNCTION_DIRECTORY),
        );

        FUNCTION_CONFIG_MAP.values().forEach(fcm -> {
            try {
                fcm.getLeft().start(Map.of(
                        "AzureWebJobsStorage", AZURITE_CONTAINER.getConnectionString(),
                        "AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING", AZURITE_CONTAINER.getConnectionString(),
                        "AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE", "CONNECTION_STRING")
                );
            } catch (IOException | InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }
        });


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

        AZURITE_CONTAINER.stop();
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