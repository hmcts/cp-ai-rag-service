package uk.gov.moj.cp.orchestrator;

import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponseCondition;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration Test for an Azure Durable Function Orchestration. Extends FunctionTestBase to manage
 * the local Azure Function host lifecycle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OrchestrationIntegrationTest extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationIntegrationTest.class);

    @Test
    @Order(1)
    @DisplayName("1. Should successfully start the Durable Function orchestration")
    void test_start_orchestration() throws InterruptedException, TimeoutException {


        // Step 1 - upload file for ingestion
        final UUID documentId = UUID.randomUUID();
        final String documentName = AZURITE_CONTAINER.uploadFile("documents", "London is the capital of France and Paris is the capital of UK", documentId);

        // Step 2 - Check status of uploaded document

        final RequestSpecification requestSpecification = getRequestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .queryParam("document-name", documentName)
                .contentType("application/json");

        pollForResponseCondition(requestSpecification, "/DocumentStatusCheck",
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("status").equals("INGESTION_SUCCESS"));


//        String payload = """
//                    {
//                      "userQuery": "test user query",
//                      "queryPrompt": "test user query prompt",
//                      "metadataFilter": [
//                        {
//                          "key": "document_id",
//                          "value": "3e3d3c13-d958-4db1-b54a-97cdc0320f58"
//                        }
//                      ]
//                    }
//                """;
//
//        // 1. Send the initial request to the HTTP trigger of the orchestrator
//        response = getRequestSpecification(ANSWER_RETRIEVAL_FUNCTION)
//                .contentType("application/json")
//                .body(payload)
//                .when()
//                .post("/AnswerRetrieval")
//                .then()
//                .statusCode(200) // Expected Accepted status for Durable Function start
//                .extract().response();

    }
}