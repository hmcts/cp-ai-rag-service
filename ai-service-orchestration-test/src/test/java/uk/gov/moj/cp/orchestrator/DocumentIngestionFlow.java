package uk.gov.moj.cp.orchestrator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.moj.cp.orchestrator.util.ContractValidator.assertMatchesContract;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.postRequest;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.moj.cp.orchestrator.util.RestOperation;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Client-agnostic building blocks for the upload → ingest flow, driven through the HTTP surfaces.
 * The caller supplies the request specification (which carries the client identity), so the same
 * steps serve both the single-client happy path and the multi-client isolation checks without
 * duplicating the plumbing.
 */
final class DocumentIngestionFlow {

    /** Ingestion (Document Intelligence → chunk → embed → index) needs more headroom than the default poll timeout. */
    private static final Duration INGESTION_TIMEOUT = Duration.ofMinutes(3);

    private DocumentIngestionFlow() {
    }

    /** Initiates an upload against the metadata-check host and returns the contract-validated SAS location. */
    static FileStorageLocationReturnedSuccessfully initiateUpload(final RequestSpecification metadataCheckSpec,
                                                                  final DocumentUploadRequest documentUploadRequest) {
        final Response response = postRequest(
                metadataCheckSpec.body(documentUploadRequest).contentType("application/json"),
                "/document-upload",
                r -> r.getStatusCode() == 200);
        assertNotNull(response);
        return assertMatchesContract(response, FileStorageLocationReturnedSuccessfully.class);
    }

    /** Polls the status host until the given document reference reports INGESTION_SUCCESS. */
    static void awaitIngestionSuccess(final RequestSpecification statusCheckSpec,
                                      final String documentReference) throws TimeoutException {
        final Response response = pollForResponse(
                statusCheckSpec.contentType("application/json"),
                RestOperation.GET,
                "/document-upload/" + documentReference,
                r -> r.getStatusCode() == 200 && "INGESTION_SUCCESS".equals(r.jsonPath().getString("status")),
                INGESTION_TIMEOUT);
        assertNotNull(response);
        assertMatchesContract(response, DocumentIngestionStatusReturnedSuccessfully.class);
    }
}
