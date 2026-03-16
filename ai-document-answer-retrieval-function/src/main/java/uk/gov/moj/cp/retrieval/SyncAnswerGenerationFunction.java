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
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.validation.RequestValidator.validate;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.transformChunkEntries;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullySynchronously;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

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

    public SyncAnswerGenerationFunction() {
        embedDataService = new EmbedDataService();
        searchService = new AzureAISearchService();
        responseGenerationService = new ResponseGenerationService();
        blobPersistenceService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS));
    }

    public SyncAnswerGenerationFunction(final EmbedDataService embedDataService, final AzureAISearchService searchService, final ResponseGenerationService responseGenerationService, final BlobPersistenceService blobPersistenceService) {
        this.embedDataService = embedDataService;
        this.searchService = searchService;
        this.responseGenerationService = responseGenerationService;
        this.blobPersistenceService = blobPersistenceService;
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
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION) HttpRequestMessage<AnswerUserQueryRequest> request,
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        try {
            final AnswerUserQueryRequest userQueryRequest = request.getBody();
            final List<String> errors = validate(userQueryRequest);
            if (!errors.isEmpty()) {
                final String errorMessage = convert(new RequestErrored(join(", ", errors)));
                return generateResponse(request, BAD_REQUEST, errorMessage);
            }

            final String userQuery = userQueryRequest.getUserQuery();
            final String userQueryPrompt = userQueryRequest.getQueryPrompt();
            final List<KeyValuePair> metadataFilters = userQueryRequest.getMetadataFilter().stream().map(uqr -> new KeyValuePair(uqr.getKey(), uqr.getValue())).toList();

            LOGGER.info("Initiating answer generation process for query - {}", userQuery);

            final List<Float> queryEmbeddings = embedDataService.getEmbedding(userQuery);

            final List<ChunkedEntry> chunkedEntries = searchService.search(userQuery, queryEmbeddings, metadataFilters);

            final LlmResponse llmResponse = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

            LOGGER.info("Answer retrieval processing completed successfully for query: {}", userQuery);

            final UserQueryAnswerReturnedSuccessfullySynchronously queryResponse = new UserQueryAnswerReturnedSuccessfullySynchronously(userQuery, llmResponse.formattedLlmResponse(), userQueryPrompt, transformChunkEntries(chunkedEntries));

            final String responseAsString = convert(queryResponse);

            final String filename = "llm-answer-with-chunks-" + randomUUID() + ".json";
            blobPersistenceService.saveBlob(
                    filename,
                    getObjectMapper().writeValueAsString(
                            new ScoringPayload(
                                    userQuery,
                                    llmResponse.formattedLlmResponse(),
                                    userQueryPrompt,
                                    chunkedEntries,
                                    null
                            )
                    )
            );

            ScoringQueuePayload scoringQueuePayload = new ScoringQueuePayload(filename);
            message.setValue(convert(scoringQueuePayload));

            return generateResponse(request, OK, responseAsString);

        } catch (Exception e) {
            LOGGER.error("Error processing answer retrieval for request: {}", request, e);
            final String errorMessage = convert(new RequestErrored("An internal error occurred: " + e.getMessage()));
            return generateResponse(request, INTERNAL_SERVER_ERROR, errorMessage);
        }
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
