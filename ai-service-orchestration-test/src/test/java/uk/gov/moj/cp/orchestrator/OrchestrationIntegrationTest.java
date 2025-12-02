package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadFile;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponseCondition;

import uk.gov.moj.cp.orchestrator.util.RestOperation;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration Test for an Azure Durable Function Orchestration. Extends FunctionTestBase to manage
 * the local Azure Function host lifecycle.
 */
@Disabled("Disabled until Azure Functions can be run in CI environment")
public class OrchestrationIntegrationTest extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationIntegrationTest.class);

    @Test
    @DisplayName("Upload file, check upload status, and retrieve answer for questions about the document")
    void test_start_orchestration() throws InterruptedException, TimeoutException {


        // Step 1 - upload file for ingestion
        final UUID documentId1 = randomUUID();
        final String documentName1 = uploadFile(BLOB_STORAGE_ACCOUNT_ENDPOINT, DOCUMENT_LANDING_FOLDER, "test-doc-capital.pdf", documentId1);

        // Step 2 - Check status of uploaded document
        final RequestSpecification documentStatusRequestSpecification = getRequestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .queryParam("document-name", documentName1)
                .contentType("application/json");



        pollForResponseCondition(documentStatusRequestSpecification, RestOperation.GET, "/DocumentStatusCheck",
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("status").equals("INGESTION_SUCCESS"));


        // Step 2 - Query against the uploaded document
        String payload = """
                    {
                      "userQuery": "Capital of UK",
                      "queryPrompt": "Capital of UK",
                      "metadataFilter": [
                        {
                          "key": "document_id",
                          "value": "%s"
                        }
                      ]
                    }
                """.formatted(documentId1.toString());

        final RequestSpecification llmQueryRequestSpecification = getRequestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(payload)
                .contentType("application/json");
        pollForResponseCondition(llmQueryRequestSpecification, RestOperation.POST, "/AnswerRetrieval",
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("llmResponse").contains("Paris"));

    }
}