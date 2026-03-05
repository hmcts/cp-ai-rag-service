package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadFile;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.postRequest;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.orchestrator.util.RestOperation;

import java.util.UUID;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration Test for an Azure Durable Function Orchestration. Extends FunctionTestBase to manage
 * the local Azure Function host lifecycle.
 */
public class OrchestrationIntegrationTest extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationIntegrationTest.class);

    @Test
    @DisplayName("Upload file with metadata, check upload status, and retrieve answer for questions about the document")
    void testUploadToBlobContainerWithMetadataAndResponseGeneration() throws InterruptedException, TimeoutException {

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

    @Test
    @DisplayName("Submit metadata, upload file using generated SAS URL, check upload status, and retrieve answer for questions about the document")
    void testUploadApiAndResponseGeneration() throws JsonProcessingException, InterruptedException, TimeoutException {

        // Step 1 - submit document metadata
        final UUID documentId = randomUUID();
        final UUID caseId = randomUUID();
        final String documentName = "test-doc-capital.pdf";

        // Step 2 - Submit document metadata and get SAS URL for upload
        DocumentUploadRequest documentUploadRequest = new DocumentUploadRequest()
                .documentId(documentId.toString())
                .documentName(documentName)
                .addMetadataFilterItem(new MetadataFilter("caseId", caseId.toString()));

        final RequestSpecification metadataSubmissionRequestSpecification = getRequestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body(documentUploadRequest)
                .contentType("application/json");


        final Response metadataSubmissionResponse = postRequest(metadataSubmissionRequestSpecification, "/document-upload",
                response -> response.getStatusCode() == 200);
        assertNotNull(metadataSubmissionResponse);
        FileStorageLocationReturnedSuccessfully fileStorageLocationReturnedSuccessfully = getObjectMapper().readValue(metadataSubmissionResponse.body().jsonPath().prettyPrint(), FileStorageLocationReturnedSuccessfully.class);
        final String documentReference = fileStorageLocationReturnedSuccessfully.getDocumentReference();
        final String uploadUrl = fileStorageLocationReturnedSuccessfully.getStorageUrl();
        LOGGER.info("Received document reference {} and upload URL {}", documentReference, uploadUrl);

        uploadFile(uploadUrl, documentName);
        LOGGER.info("Successfully uploaded file with reference {} and to URL {}", documentReference, uploadUrl);

        //check document upload status
        final RequestSpecification documentUploadStatusRequestSpecification = getRequestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .contentType("application/json");

        final Response documentStatusResponse = pollForResponse(documentUploadStatusRequestSpecification, RestOperation.GET, "/document-upload/" + documentReference,
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("status").equals("AWAITING_INGESTION"));
        assertNotNull(documentStatusResponse);
    }
}