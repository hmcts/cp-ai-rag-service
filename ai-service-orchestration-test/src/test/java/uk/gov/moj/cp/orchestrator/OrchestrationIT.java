package uk.gov.moj.cp.orchestrator;

import static java.util.UUID.randomUUID;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GROUNDEDNESS_SCORE;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.ANSWER_RETRIEVAL_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_METADATA_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.FunctionAppName.DOCUMENT_STATUS_CHECK_FUNCTION;
import static uk.gov.moj.cp.orchestrator.util.BlobUtil.uploadFile;
import static uk.gov.moj.cp.orchestrator.util.ContractValidator.assertMatchesContract;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.pollForResponse;
import static uk.gov.moj.cp.orchestrator.util.RestPoller.postRequest;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullySynchronously;
import uk.gov.moj.cp.orchestrator.extension.FunctionTestBase;
import uk.gov.moj.cp.orchestrator.util.QueueUtil;
import uk.gov.moj.cp.orchestrator.util.RestOperation;
import uk.gov.moj.cp.orchestrator.util.TableUtil;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end integration test for the RAG pipeline: it runs all five function apps as local
 * {@code func} hosts against real Azure and drives upload → ingestion → retrieval → answer
 * generation through their HTTP surfaces. Extends FunctionTestBase, whose extension provides
 * the shared once-per-run harness (function hosts + Azure test resources).
 *
 * <p><b>Grounding canaries:</b> the fixture PDFs describe an entirely fictional tribunal case
 * (Crown v. Obadiah Trellis) whose proper nouns — the vessel {@code Zephyrbrook}, adjudicators
 * {@code Fenwick} and {@code Dunbrooke} — exist nowhere in the model's world knowledge. An
 * answer can only contain them if it was grounded in the retrieved chunks. Each fixture carries
 * a disjoint token set, so tests also assert the <i>absence</i> of other fixtures' tokens,
 * turning every answer check into a metadata-filter isolation check: the supersede test in
 * particular proves the answer flipped from v1's adjudicator to v2's.</p>
 */
public class OrchestrationIT extends FunctionTestBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationIT.class);

    /** Ingestion (Document Intelligence → chunk → embed → index) needs more headroom than the default poll timeout. */
    private static final Duration INGESTION_TIMEOUT = Duration.ofMinutes(3);

    private static final String TRIBUNAL_CASE_DOCUMENT = "test-doc-tribunal-case.pdf";
    private static final String APPEAL_V1_DOCUMENT = "test-doc-tribunal-appeal-v1.pdf";
    private static final String APPEAL_V2_DOCUMENT = "test-doc-tribunal-appeal-v2.pdf";

    /** Fictional canary tokens, unique to one fixture each (see class Javadoc). */
    private static final String CASE_TOKEN = "Zephyrbrook";
    private static final String APPEAL_V1_TOKEN = "Fenwick";
    private static final String APPEAL_V2_TOKEN = "Dunbrooke";

    private static final String PENALTY_QUERY = "What penalty did the tribunal impose on Obadiah Trellis and which vessel was involved?";
    private static final String SUPERVISOR_QUERY = "Who supervises Obadiah Trellis following the appeal?";

    /** Exercises the instruction channel (not embedded; rendered under USER QUERY INSTRUCTION) and keeps token assertions deterministic. */
    private static final String QUERY_PROMPT_INSTRUCTION = "State names exactly as written in the source documents. Answer in at most three sentences.";

    private static final String ANSWER_GENERATION_PAYLOAD = """
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

    /** Shape of the worker's queue message (AnswerGenerationQueuePayload) — used to simulate a duplicate delivery. */
    private static final String ANSWER_GENERATION_QUEUE_MESSAGE = """
                {
                  "transactionId": "%s",
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

    /** Shape of the ingestion worker's queue message (QueueIngestionMetadata) — used to simulate a duplicate delivery. */
    private static final String INGESTION_QUEUE_MESSAGE = """
                {
                  "documentId": "%s",
                  "documentName": "%s",
                  "metadata": {
                    "document_id": "%s",
                    "documentId": "%s",
                    "caseName": "%s"
                  },
                  "blobUrl": "%s",
                  "currentTimestamp": "%s"
                }
            """;

    /** The sync endpoint's no-evidence sentinel (ResponseGenerationService.LLM_RESPONSE_NO_DATA_AVAILABLE). */
    private static final String NO_DATA_SENTINEL = "No data available matching the query.";

    /**
     * One answer-retrieval scenario: what to ask, how to filter, which fictional token the
     * grounded answer must contain, and which other fixture's token it must NOT contain
     * (filter-isolation check; may be null when no cross-document leak is possible).
     */
    private record AnswerQuery(String userQuery, String filterKey, String filterValue,
                               String expectedToken, String forbiddenToken) {

        String payload() {
            return ANSWER_GENERATION_PAYLOAD.formatted(userQuery, QUERY_PROMPT_INSTRUCTION, filterKey, filterValue);
        }

        String queueMessage(final String transactionId) {
            return ANSWER_GENERATION_QUEUE_MESSAGE.formatted(transactionId, userQuery, QUERY_PROMPT_INSTRUCTION, filterKey, filterValue);
        }

        boolean isGroundedAnswer(final Response response) {
            final String llmResponse = response.jsonPath().getString("llmResponse");
            return llmResponse != null
                    && llmResponse.contains(expectedToken)
                    && (forbiddenToken == null || !llmResponse.contains(forbiddenToken));
        }
    }

    /**
     * One document ingested once per class (see {@link #ingestSharedDocument()}) and shared by
     * the query-focused tests: its metadata carries every filter key those tests need, so they
     * don't each pay for their own ingestion (Document Intelligence + embeddings + indexing).
     * Tests that exercise upload/overwrite behaviour itself (supersede) still ingest their own
     * documents.
     */
    private record SharedDocument(String documentReference, String apostropheCaseName) {
    }

    private static SharedDocument sharedDocument;

    /**
     * Runs after FunctionTestBase's {@code @BeforeAll} (superclass lifecycle methods run first),
     * so the function hosts are already up when this ingests.
     */
    @BeforeAll
    static void ingestSharedDocument() throws TimeoutException {
        final UUID documentId = randomUUID();
        // Apostrophe + UUID disambiguates per-run while staying under the OpenAPI 40-char limit
        // on MetadataFilter.value (an "O'" prefix + UUID = 38 chars).
        final String caseNameWithApostrophe = "O'" + randomUUID();

        final DocumentUploadRequest documentUploadRequest = new DocumentUploadRequest()
                .documentId(documentId.toString())
                .documentName(TRIBUNAL_CASE_DOCUMENT)
                .addMetadataFilterItem(new MetadataFilter("caseId", randomUUID().toString()))
                .addMetadataFilterItem(new MetadataFilter("caseName", caseNameWithApostrophe));

        final FileStorageLocationReturnedSuccessfully fileStorageLocation = initiateDocumentUpload(documentUploadRequest);
        final String documentReference = fileStorageLocation.getDocumentReference();
        LOGGER.info("Shared fixture: received document reference {} and upload URL {}", documentReference, fileStorageLocation.getStorageUrl());

        uploadFile(fileStorageLocation.getStorageUrl(), TRIBUNAL_CASE_DOCUMENT);
        checkDocumentIngestionSuccessful(documentReference);

        sharedDocument = new SharedDocument(documentReference, caseNameWithApostrophe);
    }

    @Test
    @DisplayName("Submit metadata, upload file using generated SAS URL, check upload status, and retrieve answer for questions about the document")
    void testUploadApiAndResponseGeneration() throws TimeoutException {
        // Legacy "document_id" key: guards the backwards-compatibility dual-write in
        // DocumentBlobTriggerFunction.flatten(). The key only affects the search filter, so a
        // sync-only canary check proves it matches — the async/idempotency/scoring machinery is
        // key-agnostic and verified once, below, on the modern key.
        verifySyncAnswerRetrieval(new AnswerQuery(PENALTY_QUERY, "document_id", sharedDocument.documentReference(),
                CASE_TOKEN, APPEAL_V1_TOKEN));

        // Full pipeline (sync + async + idempotency) on the modern "documentId" key
        final String transactionId = verifyAnswerRetrievalFunction(
                new AnswerQuery(PENALTY_QUERY, "documentId", sharedDocument.documentReference(),
                        CASE_TOKEN, APPEAL_V1_TOKEN));

        // The scoring worker evaluates the async answer's groundedness and merges the score back
        // onto the answer-generation row (it is not exposed via the status endpoint, so observe
        // the table directly). Judged by an LLM, so allow generation-scale headroom.
        verifyGroundednessScoreRecorded(transactionId);

        // withChunkedEntries=true returns the chunks used for generation (read back from the
        // input-chunks blob) — the only path that exercises the documentChunk contract schema.
        verifyChunkedEntriesReturned(transactionId);
    }

    @Test
    @DisplayName("A duplicate ingestion-queue delivery for an already-ingested document is skipped by the idempotency guard")
    void testIngestionIdempotency() {
        final String documentId = sharedDocument.documentReference();

        // Capture the terminal row state before the duplicate
        final Response before = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/" + documentId);
        assertEquals(200, before.getStatusCode());
        assertEquals("INGESTION_SUCCESS", before.jsonPath().getString("status"));
        final String lastUpdatedBefore = before.jsonPath().getString("lastUpdated");

        // Re-deliver the ingestion message the blob trigger produced for this document. The
        // guard sees the terminal row and must skip without re-running Document Intelligence,
        // embedding, or indexing.
        final String blobUrl = harness().blobStorageAccountEndpoint().replaceAll("/$", "")
                + "/" + harness().documentLandingFolder() + "/" + documentId + ".pdf";
        QueueUtil.sendMessage(harness().queueStorageAccountEndpoint(), harness().documentIngestionQueue(),
                INGESTION_QUEUE_MESSAGE.formatted(documentId, TRIBUNAL_CASE_DOCUMENT, documentId, documentId,
                        sharedDocument.apostropheCaseName(), blobUrl, Instant.now().toString()));

        QueueUtil.awaitQueueDrained(harness().queueStorageAccountEndpoint(), harness().documentIngestionQueue(), Duration.ofSeconds(60));

        // Queue drained proves the duplicate was consumed; the untouched row proves it was skipped
        final Response after = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .get("/document-upload/" + documentId);
        assertEquals(200, after.getStatusCode());
        assertEquals("INGESTION_SUCCESS", after.jsonPath().getString("status"));
        assertEquals(lastUpdatedBefore, after.jsonPath().getString("lastUpdated"),
                "A duplicate ingestion delivery must not touch the terminal status row");
    }

    @Test
    @DisplayName("Apostrophe in metadata filter value survives the OData filter round-trip")
    void testApostropheInMetadataFilterValue() throws TimeoutException {
        // Pre-fix: an apostrophe in the metadata filter value terminates the OData string literal
        // early; Azure AI Search returns HTTP 400 and the answer-retrieval call fails with 5xx
        // (SearchServiceException). Post-fix: doubled-quote escaping produces a valid filter and
        // the document is retrieved successfully. The shared fixture carries the
        // apostrophe-bearing caseName in its metadata, so no dedicated ingestion is needed.

        // Query using the apostrophe-bearing metadata value as the filter — this is the path
        // that exercises the live Azure OData parser through the escape logic.
        verifyAnswerRetrievalFunction(new AnswerQuery(PENALTY_QUERY, "caseName", sharedDocument.apostropheCaseName(),
                CASE_TOKEN, APPEAL_V1_TOKEN));
    }

    @Test
    @DisplayName("Supersede replaces the old appeal determination: the answer flips from v1's adjudicator to v2's")
    void testSupersedeOldVersionOfTheDocument() throws TimeoutException {

        // Step 1 - ingest appeal v1 (first determination: supervision assigned to Fenwick)
        final UUID documentIdV1 = randomUUID();
        final UUID caseId = randomUUID();

        final DocumentUploadRequest documentUploadRequest = new DocumentUploadRequest()
                .documentId(documentIdV1.toString())
                .documentName(APPEAL_V1_DOCUMENT)
                .addMetadataFilterItem(new MetadataFilter("caseId", caseId.toString()));

        final FileStorageLocationReturnedSuccessfully fileStorageLocationV1 = initiateDocumentUpload(documentUploadRequest);
        final String documentReference = fileStorageLocationV1.getDocumentReference();
        uploadFile(fileStorageLocationV1.getStorageUrl(), APPEAL_V1_DOCUMENT);
        checkDocumentIngestionSuccessful(documentReference);

        // Step 2 - v1 answers the supervision question with its adjudicator
        verifyAnswerRetrievalFunction(new AnswerQuery(SUPERVISOR_QUERY, "documentId", documentReference,
                APPEAL_V1_TOKEN, APPEAL_V2_TOKEN));

        LOGGER.info("Version1 :: Successfully generated answers for version1 of the documentId {}", documentIdV1);

        // Step 3 - ingest appeal v2 (final determination: supervision reassigned to Dunbrooke),
        // superseding v1 via overwrites
        final UUID documentIdV2 = randomUUID();
        final DocumentUploadRequest documentUploadRequestV2 = new DocumentUploadRequest()
                .documentId(documentIdV2.toString())
                .overwrites(List.of(documentIdV1.toString()))
                .documentName(APPEAL_V2_DOCUMENT)
                .addMetadataFilterItem(new MetadataFilter("caseId", caseId.toString()));

        final FileStorageLocationReturnedSuccessfully fileStorageLocationV2 = initiateDocumentUpload(documentUploadRequestV2);
        LOGGER.info("Version2 :: Received document reference {} and upload URL {}", fileStorageLocationV2.getDocumentReference(), fileStorageLocationV2.getStorageUrl());
        uploadFile(fileStorageLocationV2.getStorageUrl(), APPEAL_V2_DOCUMENT);
        checkDocumentIngestionSuccessful(fileStorageLocationV2.getDocumentReference());

        // Step 4 - the answer flip, filtered by the caseId BOTH versions share: if supersession
        // failed, v1 would still match the filter and its adjudicator would leak into the answer
        // — which the forbidden-token check catches. A documentId filter could not detect that.
        verifyAnswerRetrievalFunction(new AnswerQuery(SUPERVISOR_QUERY, "caseId", caseId.toString(),
                APPEAL_V2_TOKEN, APPEAL_V1_TOKEN));

        // Step 5 - completeness: a query filtered to v1's documentId must yield the no-data
        // sentinel. Deactivation happens during v2's ingestion and the index applies it
        // asynchronously, so poll for the transition.
        verifyNoAnswerForFilter(SUPERVISOR_QUERY, "documentId", documentReference);
    }

    private void verifyGroundednessScoreRecorded(final String transactionId) {
        final AtomicReference<Object> score = new AtomicReference<>();
        await()
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> {
                    score.set(TableUtil.getEntityProperty(harness().tableStorageAccountEndpoint(), harness().answerGenerationTable(),
                            transactionId, transactionId, TC_RESPONSE_GROUNDEDNESS_SCORE));
                    return score.get() != null;
                });
        final double groundednessScore = new BigDecimal(score.get().toString()).doubleValue();
        assertTrue(groundednessScore > 0,
                "Groundedness score should be positive but was " + groundednessScore);
    }

    /** Polls the sync endpoint until the filter yields the no-evidence sentinel (e.g. after supersede deactivates a document). */
    private void verifyNoAnswerForFilter(final String userQuery, final String filterKey, final String filterValue) throws TimeoutException {
        final RequestSpecification llmQueryRequestSpecification = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(ANSWER_GENERATION_PAYLOAD.formatted(userQuery, QUERY_PROMPT_INSTRUCTION, filterKey, filterValue))
                .contentType("application/json");

        final Response response = pollForResponse(llmQueryRequestSpecification, RestOperation.POST, "/answer-user-query",
                r -> r.getStatusCode() == 200 &&
                        NO_DATA_SENTINEL.equals(r.jsonPath().getString("llmResponse")));
        assertMatchesContract(response, UserQueryAnswerReturnedSuccessfullySynchronously.class);
    }

    private void verifyChunkedEntriesReturned(final String transactionId) {
        final Response response = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .get("/answer-user-query-async-status/" + transactionId + "?withChunkedEntries=true");
        assertEquals(200, response.getStatusCode());
        final UserQueryAnswerReturnedSuccessfullyAsynchronously answer =
                assertMatchesContract(response, UserQueryAnswerReturnedSuccessfullyAsynchronously.class);
        assertFalse(answer.getDocumentChunks().isEmpty(),
                "withChunkedEntries=true should include the chunks used to generate the answer");
    }

    private static FileStorageLocationReturnedSuccessfully initiateDocumentUpload(final DocumentUploadRequest documentUploadRequest) {
        final RequestSpecification metadataSubmissionRequestSpecification = harness().requestSpecification(DOCUMENT_METADATA_CHECK_FUNCTION)
                .body(documentUploadRequest)
                .contentType("application/json");
        final Response metadataSubmissionResponse = postRequest(metadataSubmissionRequestSpecification, "/document-upload",
                response -> response.getStatusCode() == 200);
        assertNotNull(metadataSubmissionResponse);
        return assertMatchesContract(metadataSubmissionResponse, FileStorageLocationReturnedSuccessfully.class);
    }

    private static void checkDocumentIngestionSuccessful(final String documentReference) throws TimeoutException {
        final RequestSpecification documentUploadStatusRequestSpecification = harness().requestSpecification(DOCUMENT_STATUS_CHECK_FUNCTION)
                .contentType("application/json");

        final Response documentStatusResponse = pollForResponse(documentUploadStatusRequestSpecification, RestOperation.GET, "/document-upload/" + documentReference,
                response -> response.getStatusCode() == 200 &&
                        response.jsonPath().getString("status").equals("INGESTION_SUCCESS"),
                INGESTION_TIMEOUT);
        assertNotNull(documentStatusResponse);
        assertMatchesContract(documentStatusResponse, DocumentIngestionStatusReturnedSuccessfully.class);
    }

    /**
     * Sync-only answer check: polls {@code POST /answer-user-query} until the answer is grounded
     * (expected token present, forbidden token absent — polling through index-visibility and
     * supersede transitions), and contract-validates the response. Cheap relative to the full
     * verification — no async flow, no idempotency cycle, no scoring. A miss is cheap: with no
     * chunks retrieved the function returns the no-data sentinel without calling the LLM.
     */
    private void verifySyncAnswerRetrieval(final AnswerQuery query) throws TimeoutException {
        final RequestSpecification llmQueryRequestSpecification = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(query.payload())
                .contentType("application/json");

        final Response llmAnswerResponse = pollForResponse(llmQueryRequestSpecification, RestOperation.POST, "/answer-user-query",
                response -> response.getStatusCode() == 200 &&
                        query.isGroundedAnswer(response));
        assertNotNull(llmAnswerResponse);
        assertMatchesContract(llmAnswerResponse, UserQueryAnswerReturnedSuccessfullySynchronously.class);
    }

    /** Returns the transactionId of the async answer generation it drove, for follow-on assertions. */
    private String verifyAnswerRetrievalFunction(final AnswerQuery query) throws TimeoutException {
        // Step 3 - Query synchronously against the uploaded document
        verifySyncAnswerRetrieval(query);

        final RequestSpecification llmQueryRequestSpecification = harness().requestSpecification(ANSWER_RETRIEVAL_FUNCTION)
                .body(query.payload())
                .contentType("application/json");

        // Step 4 - Query asynchronously against the uploaded document (202 Accepted per contract)
        final Response asyncResponse = postRequest(llmQueryRequestSpecification, "/answer-user-query-async",
                response -> response.getStatusCode() == 202 &&
                        !response.jsonPath().getString("transactionId").isEmpty());

        final String transactionId = assertMatchesContract(asyncResponse, UserQueryAnswerRequestAccepted.class)
                .getTransactionId();

        // Step 5 - Check asynchronous answer generation (llmResponse is null while the row is
        // still ANSWER_GENERATION_PENDING, so the condition must be null-safe)
        final Response answerGenerationResponse = pollForResponse(llmQueryRequestSpecification, RestOperation.GET, "/answer-user-query-async-status/" + transactionId,
                response -> response.getStatusCode() == 200 &&
                        query.isGroundedAnswer(response)
        );

        assertNotNull(answerGenerationResponse);
        assertMatchesContract(answerGenerationResponse, UserQueryAnswerReturnedSuccessfullyAsynchronously.class);

        // Step 6 - Idempotency: redeliver the same queue message for the completed transaction.
        // The guard must skip it — no second LLM run. Waiting for the queue to drain proves the
        // duplicate was consumed; the generation timestamp/duration staying identical proves the
        // worker did not regenerate (a rerun would change them even if the text came out equal).
        final String completedLlmResponse = answerGenerationResponse.jsonPath().getString("llmResponse");
        final String completedGenerationTime = answerGenerationResponse.jsonPath().getString("responseGenerationTime");
        final String completedGenerationDuration = answerGenerationResponse.jsonPath().getString("responseGenerationDuration");

        QueueUtil.sendMessage(harness().queueStorageAccountEndpoint(), harness().answerGenerationQueue(),
                query.queueMessage(transactionId));

        QueueUtil.awaitQueueDrained(harness().queueStorageAccountEndpoint(), harness().answerGenerationQueue(), Duration.ofSeconds(60));

        final Response afterDuplicateResponse = pollForResponse(llmQueryRequestSpecification, RestOperation.GET, "/answer-user-query-async-status/" + transactionId,
                response -> response.getStatusCode() == 200 &&
                        "ANSWER_GENERATED".equals(response.jsonPath().getString("status"))
        );
        assertEquals(completedLlmResponse, afterDuplicateResponse.jsonPath().getString("llmResponse"),
                "A duplicate delivery must not regenerate the answer");
        assertEquals(completedGenerationTime, afterDuplicateResponse.jsonPath().getString("responseGenerationTime"),
                "A duplicate delivery must not update the generation timestamp");
        assertEquals(completedGenerationDuration, afterDuplicateResponse.jsonPath().getString("responseGenerationDuration"),
                "A duplicate delivery must not update the generation duration");

        return transactionId;
    }
}
