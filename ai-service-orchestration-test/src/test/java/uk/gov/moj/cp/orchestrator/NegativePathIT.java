package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadBytes;
import static uk.gov.moj.cp.orchestrator.util.ContractValidator.assertMatchesContract;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.moj.cp.orchestrator.extension.FunctionTestBase;
import uk.gov.moj.cp.orchestrator.util.RestOperation;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Negative-path and contract tests for the HTTP surfaces: validation rejections, duplicate
 * handling, unknown references, and the file-size limit. None of these invoke the LLM or run a
 * full ingestion, so they add little wall-clock time and no Azure OpenAI spend. Response bodies
 * are checked against the generated OpenAPI models via {@code ContractValidator}.
 *
 * <p>The file-size test relies on {@code MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB=1} set in
 * {@code RagHarness.setupEnvVarMap()}.</p>
 */
public class NegativePathIT extends FunctionTestBase {

    /** Blob-trigger detection is scan-based locally and can lag past a minute — same headroom as the ingestion poll. */
    private static final Duration BLOB_TRIGGER_TIMEOUT = Duration.ofMinutes(3);

    @Test
    @DisplayName("Upload initiation with a non-UUID documentId is rejected with 400")
    void uploadRejectedForInvalidDocumentId() {
        final Response response = harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body("""
                        {
                          "documentId": "not-a-uuid",
                          "documentName": "test-doc-tribunal-case.pdf",
                          "metadataFilter": [{"key": "caseId", "value": "%s"}]
                        }
                        """.formatted(randomUUID()))
                .contentType("application/json")
                .post("/document-upload");

        assertEquals(400, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }

    @Test
    @DisplayName("Upload initiation without a documentName is rejected with 400")
    void uploadRejectedForMissingDocumentName() {
        final Response response = harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body("""
                        {
                          "documentId": "%s",
                          "metadataFilter": [{"key": "caseId", "value": "%s"}]
                        }
                        """.formatted(randomUUID(), randomUUID()))
                .contentType("application/json")
                .post("/document-upload");

        assertEquals(400, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }

    @Test
    @DisplayName("A second upload initiation for the same documentId is rejected with 400")
    void duplicateUploadInitiationRejected() {
        final DocumentUploadRequest request = new DocumentUploadRequest()
                .documentId(randomUUID().toString())
                .documentName("test-doc-tribunal-case.pdf")
                .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString()));

        final Response first = postUploadRequest(request);
        assertEquals(200, first.getStatusCode());
        assertMatchesContract(first, FileStorageLocationReturnedSuccessfully.class);

        final Response second = postUploadRequest(request);
        assertEquals(400, second.getStatusCode());
        final RequestErrored error = assertMatchesContract(second, RequestErrored.class);
        assertTrue(error.getErrorMessage().contains("already been initiated"),
                "Expected duplicate-initiation message but got: " + error.getErrorMessage());
    }

    @Test
    @DisplayName("Answer queries without a userQuery are rejected with 400 on both sync and async endpoints")
    void answerQueryRejectedForMissingUserQuery() {
        final String invalidQueryPayload = """
                {
                  "queryPrompt": "Capital of UK",
                  "metadataFilter": [{"key": "documentId", "value": "%s"}]
                }
                """.formatted(randomUUID());

        final Response syncResponse = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(invalidQueryPayload)
                .contentType("application/json")
                .post("/answer-user-query");
        assertEquals(400, syncResponse.getStatusCode());
        assertMatchesContract(syncResponse, RequestErrored.class);

        final Response asyncResponse = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(invalidQueryPayload)
                .contentType("application/json")
                .post("/answer-user-query-async");
        assertEquals(400, asyncResponse.getStatusCode());
        assertMatchesContract(asyncResponse, RequestErrored.class);
    }

    @Test
    @DisplayName("Document status with a non-UUID reference is rejected with 400")
    void documentStatusRejectedForInvalidReference() {
        final Response response = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/not-a-uuid");

        assertEquals(400, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }

    @Test
    @DisplayName("Document status for an unknown reference returns 404")
    void documentStatusNotFoundForUnknownReference() {
        final Response response = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/" + randomUUID());

        assertEquals(404, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }

    @Test
    @DisplayName("Answer status with a non-UUID transactionId is rejected with 400")
    void answerStatusRejectedForInvalidTransactionId() {
        final Response response = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .get("/answer-user-query-async-status/not-a-uuid");

        assertEquals(400, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }

    @Test
    @DisplayName("Answer status for an unknown transactionId returns 404")
    void answerStatusNotFoundForUnknownTransactionId() {
        final Response response = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .get("/answer-user-query-async-status/" + randomUUID());

        assertEquals(404, response.getStatusCode());
        final RequestErrored error = assertMatchesContract(response, RequestErrored.class);
        assertTrue(error.getErrorMessage().contains("No Answer request found"),
                "Expected not-found message but got: " + error.getErrorMessage());
    }

    @Test
    @DisplayName("Status is AWAITING_UPLOAD immediately after initiation, before any file is uploaded")
    void statusIsAwaitingUploadAfterInitiate() {
        final DocumentUploadRequest request = new DocumentUploadRequest()
                .documentId(randomUUID().toString())
                .documentName("test-doc-tribunal-case.pdf")
                .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString()));

        final Response initiateResponse = postUploadRequest(request);
        assertEquals(200, initiateResponse.getStatusCode());
        final FileStorageLocationReturnedSuccessfully location =
                assertMatchesContract(initiateResponse, FileStorageLocationReturnedSuccessfully.class);

        final Response statusResponse = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/" + location.getDocumentReference());
        assertEquals(200, statusResponse.getStatusCode());
        final DocumentIngestionStatusReturnedSuccessfully status =
                assertMatchesContract(statusResponse, DocumentIngestionStatusReturnedSuccessfully.class);
        assertEquals(DocumentIngestionStatus.AWAITING_UPLOAD, status.getStatus());
    }

    @Test
    @DisplayName("A blob over the size limit is marked FILE_SIZE_OVER_LIMIT and never ingested")
    void oversizedUploadMarkedFileSizeOverLimit() throws TimeoutException {
        final DocumentUploadRequest request = new DocumentUploadRequest()
                .documentId(randomUUID().toString())
                .documentName("oversized.pdf")
                .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString()));

        final Response initiateResponse = postUploadRequest(request);
        assertEquals(200, initiateResponse.getStatusCode());
        final FileStorageLocationReturnedSuccessfully location =
                assertMatchesContract(initiateResponse, FileStorageLocationReturnedSuccessfully.class);

        // 2 MiB of zeros — over the 1 MiB test limit; content is irrelevant because the size
        // check happens before any parsing
        uploadBytes(location.getStorageUrl(), new byte[2 * 1024 * 1024]);

        final Response statusResponse = pollForResponse(
                harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION),
                RestOperation.GET,
                "/document-upload/" + location.getDocumentReference(),
                response -> response.getStatusCode() == 200 &&
                        "FILE_SIZE_OVER_LIMIT".equals(response.jsonPath().getString("status")),
                BLOB_TRIGGER_TIMEOUT);

        final DocumentIngestionStatusReturnedSuccessfully status =
                assertMatchesContract(statusResponse, DocumentIngestionStatusReturnedSuccessfully.class);
        assertFalse(status.getReason() == null || status.getReason().isBlank(),
                "FILE_SIZE_OVER_LIMIT row should carry a reason explaining the limit");
    }

    private Response postUploadRequest(final DocumentUploadRequest request) {
        return harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body(request)
                .contentType("application/json")
                .post("/document-upload");
    }
}
