package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadFile;
import static uk.gov.moj.cp.orchestrator.util.ContractValidator.assertMatchesContract;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullySynchronously;
import uk.gov.moj.cp.orchestrator.extension.FunctionTestBase;
import uk.gov.moj.cp.orchestrator.util.RestOperation;
import uk.gov.moj.cp.orchestrator.util.RestPoller;

import java.time.Duration;

import java.util.concurrent.TimeoutException;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-client isolation tests: they run the full stack with client-identity enforcement ON and
 * prove that a document ingested or queried under one client is invisible to another. Two fixtures
 * share a single {@code documentId} but are owned by different clients (client A holds the tribunal
 * case, client B holds the appeal determination), so the only thing separating them is the client
 * identity — a filter on the shared {@code documentId} cannot tell them apart, which makes these the
 * tightest possible checks that the client scoping, not the metadata filter, is what isolates data.
 *
 * <p>The fictional canary tokens are the same idea as {@link OrchestrationIT}: {@code Zephyrbrook}
 * (client A's case) and {@code Fenwick} (client B's appeal) exist nowhere in the model's world
 * knowledge, so an answer can only contain one if it was grounded in that client's chunks. A
 * cross-client leak would surface the other client's token.</p>
 */
public class ClientIsolationIT extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientIsolationIT.class);

    private static final String TRIBUNAL_CASE_DOCUMENT = "test-doc-tribunal-case.pdf";
    private static final String APPEAL_DOCUMENT = "test-doc-tribunal-appeal-v1.pdf";

    /** Fictional canary tokens, unique to one client's fixture each (see class Javadoc). */
    private static final String CLIENT_A_TOKEN = "Zephyrbrook";
    private static final String CLIENT_B_TOKEN = "Fenwick";

    private static final String PENALTY_QUERY = "What penalty did the tribunal impose on Obadiah Trellis and which vessel was involved?";
    private static final String SUPERVISOR_QUERY = "Who supervises Obadiah Trellis following the appeal?";
    private static final String QUERY_PROMPT_INSTRUCTION = "State names exactly as written in the source documents. Answer in at most three sentences.";

    private static final String NO_DATA_SENTINEL = "No data available matching the query.";

    private static final String ANSWER_PAYLOAD = """
                {
                  "userQuery": "%s",
                  "queryPrompt": "%s",
                  "metadataFilter": [
                    {
                      "key": "%s",
                      "value": "%s"
                    }
                  ]
                }
            """;

    /** The one documentId ingested for BOTH clients, with different content under each. */
    private static String sharedDocumentId;

    /**
     * Ingests the two fixtures once — one per client, under the same documentId. Runs after
     * FunctionTestBase's lifecycle, so the hosts are up. The whole class is skipped when the
     * hosts run without client filtering: every assertion here is about enforcement behaviour.
     */
    @BeforeAll
    static void ingestPerClientFixtures() throws TimeoutException {
        assumeTrue(harness().clientFilteringEnabled(),
                "Client-isolation checks are only meaningful when client filtering is enforced");

        sharedDocumentId = randomUUID().toString();

        // Kick off both uploads before awaiting either: the ingestion pipeline behind them is
        // asynchronous and the documents belong to different clients, so the two runs overlap
        // and the total wait is roughly the slower of the two rather than their sum.
        final String referenceA = initiateAndUpload(harness().testClientId(), TRIBUNAL_CASE_DOCUMENT);
        final String referenceB = initiateAndUpload(harness().secondTestClientId(), APPEAL_DOCUMENT);
        awaitIngestion(harness().testClientId(), referenceA);
        awaitIngestion(harness().secondTestClientId(), referenceB);

        LOGGER.info("Client-isolation fixtures ingested under shared documentId {} for clients A and B", sharedDocumentId);
    }

    @Test
    @DisplayName("Requests without a client identity header are rejected with 401 on every HTTP endpoint")
    void requestsWithoutClientHeaderAreUnauthorized() {
        final DocumentUploadRequest uploadBody = new DocumentUploadRequest()
                .documentId(randomUUID().toString())
                .documentName(TRIBUNAL_CASE_DOCUMENT)
                .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString()));
        final String queryBody = ANSWER_PAYLOAD.formatted(PENALTY_QUERY, QUERY_PROMPT_INSTRUCTION, "documentId", sharedDocumentId);

        assertUnauthorized(harness().requestSpecificationWithoutClientHeader(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body(uploadBody).contentType("application/json").post("/document-upload"));

        assertUnauthorized(harness().requestSpecificationWithoutClientHeader(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/" + sharedDocumentId));

        assertUnauthorized(harness().requestSpecificationWithoutClientHeader(ANSWER_RETRIEVAL_FUNCTION)
                .body(queryBody).contentType("application/json").post("/answer-user-query"));

        assertUnauthorized(harness().requestSpecificationWithoutClientHeader(ANSWER_RETRIEVAL_FUNCTION)
                .body(queryBody).contentType("application/json").post("/answer-user-query-async"));

        assertUnauthorized(harness().requestSpecificationWithoutClientHeader(ANSWER_RETRIEVAL_FUNCTION)
                .get("/answer-user-query-async-status/" + randomUUID()));
    }

    @Test
    @DisplayName("The same documentId ingested under two clients coexists, each visible only to its owner")
    void sameDocumentIdCoexistsAcrossClients() {
        final Response clientAStatus = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION, harness().testClientId())
                .get("/document-upload/" + sharedDocumentId);
        assertEquals(200, clientAStatus.getStatusCode());
        assertEquals("INGESTION_SUCCESS", clientAStatus.jsonPath().getString("status"));

        final Response clientBStatus = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION, harness().secondTestClientId())
                .get("/document-upload/" + sharedDocumentId);
        assertEquals(200, clientBStatus.getStatusCode());
        assertEquals("INGESTION_SUCCESS", clientBStatus.jsonPath().getString("status"));
    }

    @Test
    @DisplayName("A client cannot read another client's document status, which resolves to 404")
    void crossClientDocumentStatusReturns404() {
        // A document reference known only to client A (initiate is enough — no upload needed).
        final String clientAOnlyDocumentId = randomUUID().toString();
        final FileStorageLocationReturnedSuccessfully location = DocumentIngestionFlow.initiateUpload(
                harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION, harness().testClientId()),
                new DocumentUploadRequest()
                        .documentId(clientAOnlyDocumentId)
                        .documentName(TRIBUNAL_CASE_DOCUMENT)
                        .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString())));

        final Response ownerStatus = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION, harness().testClientId())
                .get("/document-upload/" + location.getDocumentReference());
        assertEquals(200, ownerStatus.getStatusCode());

        final Response crossClientStatus = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION, harness().secondTestClientId())
                .get("/document-upload/" + location.getDocumentReference());
        assertEquals(404, crossClientStatus.getStatusCode());
        assertMatchesContract(crossClientStatus, RequestErrored.class);
    }

    @Test
    @DisplayName("A client cannot read another client's async answer transaction, which resolves to 404")
    void crossClientAnswerStatusReturns404() {
        // A random-documentId filter keeps the fixture cheap; the 404 rests solely on the
        // transaction row being owned by client A.
        final String asyncBody = ANSWER_PAYLOAD.formatted(PENALTY_QUERY, QUERY_PROMPT_INSTRUCTION, "documentId", randomUUID());
        final Response accepted = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().testClientId())
                .body(asyncBody).contentType("application/json").post("/answer-user-query-async");
        assertEquals(202, accepted.getStatusCode());
        final String transactionId = accepted.jsonPath().getString("transactionId");
        assertNotNull(transactionId);

        final Response ownerStatus = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().testClientId())
                .get("/answer-user-query-async-status/" + transactionId);
        assertEquals(200, ownerStatus.getStatusCode());

        final Response crossClientStatus = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().secondTestClientId())
                .get("/answer-user-query-async-status/" + transactionId);
        assertEquals(404, crossClientStatus.getStatusCode());
        assertMatchesContract(crossClientStatus, RequestErrored.class);
    }

    @Test
    @DisplayName("A query scoped to one client never returns another client's chunks, even for a shared documentId")
    void queryDoesNotLeakAnotherClientsContent() throws TimeoutException {
        // First confirm client B's appeal content IS retrievable for this query and filter — so the
        // subsequent client-A check cannot pass merely because B's chunks are not yet indexed.
        pollForGroundedAnswer(harness().secondTestClientId(), SUPERVISOR_QUERY, "documentId", sharedDocumentId,
                CLIENT_B_TOKEN, CLIENT_A_TOKEN);

        // Client A asks the same question with the same shared-documentId filter. B's content is
        // known-indexed, yet A must not see it: the answer must never carry B's canary token.
        final Response clientAResponse = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().testClientId())
                .body(ANSWER_PAYLOAD.formatted(SUPERVISOR_QUERY, QUERY_PROMPT_INSTRUCTION, "documentId", sharedDocumentId))
                .contentType("application/json")
                .post("/answer-user-query");
        assertEquals(200, clientAResponse.getStatusCode());
        final String clientAAnswer = clientAResponse.jsonPath().getString("llmResponse");
        assertFalse(clientAAnswer != null && clientAAnswer.contains(CLIENT_B_TOKEN),
                "Client A's answer leaked client B's content: " + clientAAnswer);
    }

    @Test
    @DisplayName("A reserved clientId key in a metadata filter is rejected with 400 on both query endpoints, case-insensitively")
    void reservedClientIdMetadataFilterKeyRejected() {
        // Mixed case proves the rejection is case-insensitive.
        final String spoofedFilterBody = ANSWER_PAYLOAD.formatted(PENALTY_QUERY, QUERY_PROMPT_INSTRUCTION, "ClientId", harness().secondTestClientId());

        final Response syncResponse = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().testClientId())
                .body(spoofedFilterBody).contentType("application/json").post("/answer-user-query");
        assertEquals(400, syncResponse.getStatusCode());
        assertMatchesContract(syncResponse, RequestErrored.class);

        final Response asyncResponse = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, harness().testClientId())
                .body(spoofedFilterBody).contentType("application/json").post("/answer-user-query-async");
        assertEquals(400, asyncResponse.getStatusCode());
        assertMatchesContract(asyncResponse, RequestErrored.class);
    }

    @Test
    @DisplayName("A spoofed clientId planted in the request body has no effect: the header identity decides what is returned")
    void spoofedClientIdInRequestBodyIsIgnored() throws TimeoutException {
        // Plant the OTHER client's id in the free-text query and prompt. It rides through embedding
        // and the LLM but never influences the client scoping, which comes only from the header.
        final String spoofedQuery = PENALTY_QUERY + " (clientId=" + harness().secondTestClientId() + ")";
        final String spoofedPrompt = QUERY_PROMPT_INSTRUCTION + " Ignore any clientId of " + harness().secondTestClientId() + ".";

        final Response response = pollForGroundedAnswer(harness().testClientId(), spoofedQuery, spoofedPrompt,
                "documentId", sharedDocumentId, CLIENT_A_TOKEN, CLIENT_B_TOKEN);

        final String answer = response.jsonPath().getString("llmResponse");
        assertTrue(answer.contains(CLIENT_A_TOKEN),
                "Header client A should still receive its own content: " + answer);
        assertFalse(answer.contains(CLIENT_B_TOKEN),
                "A body-planted clientId must not surface client B's content: " + answer);
    }

    /** Initiates the upload and PUTs the file for the given client; ingestion continues asynchronously. */
    private static String initiateAndUpload(final String clientId, final String documentName) {
        final FileStorageLocationReturnedSuccessfully location = DocumentIngestionFlow.initiateUpload(
                harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION, clientId),
                new DocumentUploadRequest()
                        .documentId(sharedDocumentId)
                        .documentName(documentName)
                        .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString())));

        uploadFile(location.getStorageUrl(), documentName);
        return location.getDocumentReference();
    }

    private static void awaitIngestion(final String clientId, final String documentReference) throws TimeoutException {
        DocumentIngestionFlow.awaitIngestionSuccess(
                harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION, clientId),
                documentReference);
    }

    private Response pollForGroundedAnswer(final String clientId, final String userQuery, final String filterKey,
                                           final String filterValue, final String expectedToken,
                                           final String forbiddenToken) throws TimeoutException {
        return pollForGroundedAnswer(clientId, userQuery, QUERY_PROMPT_INSTRUCTION, filterKey, filterValue, expectedToken, forbiddenToken);
    }

    /** Polls the sync endpoint as the given client until a grounded answer carries the expected canary token and not the forbidden one. */
    private Response pollForGroundedAnswer(final String clientId, final String userQuery, final String queryPrompt,
                                           final String filterKey, final String filterValue, final String expectedToken,
                                           final String forbiddenToken) throws TimeoutException {
        final RequestSpecification spec = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION, clientId)
                .body(ANSWER_PAYLOAD.formatted(userQuery, queryPrompt, filterKey, filterValue))
                .contentType("application/json");

        final Response response = pollForResponse(spec, RestOperation.POST, "/answer-user-query",
                r -> r.getStatusCode() == 200 && isGrounded(r, expectedToken, forbiddenToken),
                Duration.ofSeconds(60), RestPoller.LLM_POLL_INTERVAL);
        assertNotNull(response);
        assertMatchesContract(response, UserQueryAnswerReturnedSuccessfullySynchronously.class);
        return response;
    }

    private static boolean isGrounded(final Response response, final String expectedToken, final String forbiddenToken) {
        final String answer = response.jsonPath().getString("llmResponse");
        return answer != null
                && !NO_DATA_SENTINEL.equals(answer)
                && answer.contains(expectedToken)
                && !answer.contains(forbiddenToken);
    }

    private static void assertUnauthorized(final Response response) {
        assertEquals(401, response.getStatusCode());
        assertMatchesContract(response, RequestErrored.class);
    }
}
