package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static com.microsoft.azure.functions.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.lang.String.join;
import static java.util.UUID.randomUUID;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.validation.MetadataFilterValidator.validateReservedKeys;
import static uk.gov.moj.cp.ai.validation.RequestValidator.validate;
import static uk.gov.moj.cp.retrieval.service.ResponseGenerationService.LLM_RESPONSE_FAILURE_TO_GENERATE;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getAnswerWithChunksFilename;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.transformChunkEntries;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullySynchronously;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.client.identity.HeaderClientIdentityResolver;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.util.ArrayList;
import java.util.List;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for answer retrieval and generation. Processes client queries and  generates
 * answer summaries.
 */
public class SyncAnswerGenerationFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncAnswerGenerationFunction.class);

    private final EmbedDataService embedDataService;

    private final AzureAISearchService searchService;

    private final ResponseGenerationService responseGenerationService;

    private final BlobPersistenceService blobPersistenceService;

    private final CitationGuardMode guardMode;

    private final ClientIdentityResolver clientIdentityResolver;

    public SyncAnswerGenerationFunction() {
        embedDataService = new EmbedDataService();
        searchService = new AzureAISearchService();
        responseGenerationService = new ResponseGenerationService();
        blobPersistenceService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS));
        guardMode = CitationGuardMode.fromEnv();
        clientIdentityResolver = HeaderClientIdentityResolver.fromEnvironment();
    }

    SyncAnswerGenerationFunction(final EmbedDataService embedDataService, final AzureAISearchService searchService,
                                 final ResponseGenerationService responseGenerationService,
                                 final BlobPersistenceService blobPersistenceService,
                                 final CitationGuardMode guardMode) {
        this(embedDataService, searchService, responseGenerationService, blobPersistenceService, guardMode, null);
    }

    SyncAnswerGenerationFunction(final EmbedDataService embedDataService, final AzureAISearchService searchService,
                                 final ResponseGenerationService responseGenerationService,
                                 final BlobPersistenceService blobPersistenceService,
                                 final CitationGuardMode guardMode,
                                 final ClientIdentityResolver clientIdentityResolver) {
        this.embedDataService = embedDataService;
        this.searchService = searchService;
        this.responseGenerationService = responseGenerationService;
        this.blobPersistenceService = blobPersistenceService;
        this.guardMode = guardMode;
        this.clientIdentityResolver = clientIdentityResolver != null
                ? clientIdentityResolver : HeaderClientIdentityResolver.fromEnvironment();
    }

    /**
     * HTTP-triggered function for answer retrieval.
     *
     * @param request The HTTP request containing the query
     * @param context The execution context
     * @return HTTP response with the generated answer
     */
    @FunctionName("AnswerRetrieval")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION, route = "answer-user-query") HttpRequestMessage<AnswerUserQueryRequest> request,
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        try {
            // Client-identity resolution seam: the resolved context is not yet threaded into
            // search / the scoring payload / the answer blob name (that wiring is driven by the
            // pending tests). Flag off → an empty context, so behaviour is unchanged today.
            clientIdentityResolver.resolve(request);

            final AnswerUserQueryRequest userQueryRequest = request.getBody();
            final List<String> errors = new ArrayList<>(validate(userQueryRequest));
            if (userQueryRequest != null && userQueryRequest.getMetadataFilter() != null) {
                errors.addAll(validateReservedKeys(userQueryRequest.getMetadataFilter().stream()
                        .map(mf -> new KeyValuePair(mf.getKey(), mf.getValue())).toList()));
            }
            if (!errors.isEmpty()) {
                final String errorMessage = convert(new RequestErrored(join(", ", errors)));
                return generateResponse(request, BAD_REQUEST, errorMessage);
            }

            final String userQuery = userQueryRequest.getUserQuery();
            final String userQueryPrompt = userQueryRequest.getQueryPrompt();
            final List<KeyValuePair> metadataFilters = userQueryRequest.getMetadataFilter().stream().map(uqr -> new KeyValuePair(uqr.getKey(), uqr.getValue())).toList();

            LOGGER.info("Initiating answer generation process for query - {}", userQuery);

            final List<Float> queryEmbeddings = embedDataService.getEmbedding(userQuery);

            final List<ChunkedEntry> chunkedEntries = searchService.search(null, userQuery, queryEmbeddings, metadataFilters);

            LlmResponse llmResponse;
            try {
                llmResponse = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);
            } catch (final CitationDegradedException e) {
                llmResponse = applyGuardPolicy(e);
            }

            LOGGER.info("Answer retrieval processing completed for query: {} (status: {})", userQuery, llmResponse.status());

            final UserQueryAnswerReturnedSuccessfullySynchronously queryResponse = new UserQueryAnswerReturnedSuccessfullySynchronously(userQuery, llmResponse.formattedLlmResponse(), userQueryPrompt, transformChunkEntries(chunkedEntries));

            final String responseAsString = convert(queryResponse);

            // A guard-rejected (uncited) or failed generation carries only sentinel text —
            // nothing meaningful to score, so skip the scoring blob + enqueue.
            if (llmResponse.status() == ANSWER_GENERATION_FAILED) {
                LOGGER.warn("Skipping scoring for failed generation (reason: {})", llmResponse.reason());
                return generateResponse(request, OK, responseAsString);
            }

            final String filename = getAnswerWithChunksFilename(null, randomUUID());
            final ScoringPayload scoringPayload = new ScoringPayload(
                    userQuery, llmResponse.formattedLlmResponse(), userQueryPrompt, chunkedEntries, null);
            blobPersistenceService.saveBlob(filename, convert(scoringPayload));
            message.setValue(convert(new ScoringQueuePayload(filename)));

            return generateResponse(request, OK, responseAsString);

        } catch (Exception e) {
            LOGGER.error("Error processing answer retrieval for request: {}", request, e);
            final String errorMessage = convert(new RequestErrored("An internal error occurred: " + e.getMessage()));
            return generateResponse(request, INTERNAL_SERVER_ERROR, errorMessage);
        }
    }

    /**
     * Interactive path: no retries — the citation-guard policy applies immediately (the caller
     * can simply re-submit). REJECT maps the degraded answer to a FAILED sentinel response;
     * DELIVER returns the degraded answer carried by the exception, reason recorded.
     */
    private LlmResponse applyGuardPolicy(final CitationDegradedException e) {
        if (guardMode == CitationGuardMode.REJECT) {
            LOGGER.warn("Citation guard: rejecting uncited answer — {}", e.getMessage());
            return new LlmResponse(e.rawLlmResponse(), LLM_RESPONSE_FAILURE_TO_GENERATE,
                    ANSWER_GENERATION_FAILED, e.getMessage());
        }
        LOGGER.warn("Citation guard: delivering citation-degraded answer — {}", e.getMessage());
        return new LlmResponse(e.rawLlmResponse(), e.formattedText(), ANSWER_GENERATED, e.getMessage());
    }

    private HttpResponseMessage generateResponse(final HttpRequestMessage<?> request,
                                                 final HttpStatus status,
                                                 final String message) {
        return request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(message)
                .build();
    }
}
