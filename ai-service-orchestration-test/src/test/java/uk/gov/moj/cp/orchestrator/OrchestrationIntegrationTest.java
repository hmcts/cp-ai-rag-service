package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadFile;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.postRequest;

import uk.gov.moj.cp.orchestrator.util.RestOperation;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.restassured.response.Response;
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


        final Response documentStatusResponse = pollForResponse(documentStatusRequestSpecification, RestOperation.GET, "/DocumentStatusCheck",
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("status").equals("INGESTION_SUCCESS"));
        assertNotNull(documentStatusResponse);


        String answerGenerationPayload = """
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
                .body(answerGenerationPayload)
                .contentType("application/json");

        // Step 3 - Query synchronously against the uploaded document
        final Response llmAnswerResponse = postRequest(llmQueryRequestSpecification, "/AnswerRetrieval",
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("llmResponse").contains("Paris"));
        assertNotNull(llmAnswerResponse);

        // Step 4 - Query asynchronously against the uploaded document
        final Response asyncResponse = postRequest(llmQueryRequestSpecification, "/answer-user-query-async",
                response -> response.getStatusCode() == 200 &&
                        !response.jsonPath().getString("transactionId").isEmpty());

        final String transactionId = asyncResponse.jsonPath().getString("transactionId");

        // Step 5 - Check asynchronous answer generation
        final Response answerGenerationResponse = pollForResponse(llmQueryRequestSpecification, RestOperation.GET, "/answer-user-query-async-status/" + transactionId,
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("llmResponse").contains("Paris")
        );
        assertNotNull(answerGenerationResponse);

    }
}