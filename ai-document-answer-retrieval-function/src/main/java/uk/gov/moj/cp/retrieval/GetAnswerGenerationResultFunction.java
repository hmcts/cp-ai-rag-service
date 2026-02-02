package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.NOT_FOUND;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.lang.Boolean.parseBoolean;
import static java.util.Objects.nonNull;
import static java.util.UUID.fromString;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.retrieval.AnswerGenerationFunction.getInputChunksFilename;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.model.QueryAsyncResponse;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function to Get generated answer for the transactionId.
 */
public class GetAnswerGenerationResultFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(GetAnswerGenerationResultFunction.class);

    private final AnswerGenerationTableService answerGenerationTableService;
    private final BlobPersistenceService blobPersistenceInputChunksService;

    public GetAnswerGenerationResultFunction() {
        final String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION);
        answerGenerationTableService = new AnswerGenerationTableService(tableName);
        blobPersistenceInputChunksService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS));
    }

    public GetAnswerGenerationResultFunction(final AnswerGenerationTableService answerGenerationTableService, final BlobPersistenceService blobPersistenceInputChunksService) {
        this.answerGenerationTableService = answerGenerationTableService;
        this.blobPersistenceInputChunksService = blobPersistenceInputChunksService;
    }

    /**
     * HTTP-triggered function to Get answer.
     *
     * @param request The HTTP request containing the transactionId
     * @param context The execution context
     * @return HTTP response with the generated answer
     */
    @FunctionName("GetAnswerGeneration")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = HttpMethod.GET, authLevel = FUNCTION, route = "answer-user-query-async-status/{transactionId}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("transactionId") String transactionId,
            final ExecutionContext context
    ) {

        try {

            if (isNullOrEmpty(transactionId) || !isValid(transactionId)) {
                LOGGER.error("Error: transactionId is required");
                final String errorMessage = convert(Map.of("errorMessage", "Error: transactionId is required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            final GeneratedAnswer generatedAnswer = answerGenerationTableService.getGeneratedAnswer(transactionId);

            if (nonNull(generatedAnswer)) {
                final boolean withChunkedEntries = parseBoolean(request.getQueryParameters().getOrDefault("withChunkedEntries", "false"));
                final List<ChunkedEntry> chunkedEntriesFromBlobContainer =
                        (withChunkedEntries && !isNullOrEmpty(generatedAnswer.getChunkedEntriesFile()))
                                ? blobPersistenceInputChunksService.readBlob(getInputChunksFilename(fromString(transactionId)), InputChunksPayload.class).chunkedEntries()
                                : List.of();

                final QueryAsyncResponse queryResponse = toQueryResponse(generatedAnswer, chunkedEntriesFromBlobContainer);
                return generateResponse(request, OK, convert(queryResponse));
            }

            return generateResponse(request, NOT_FOUND, String.format("No Answer request found for the transactionId=%s", transactionId));

        } catch (Exception e) {
            LOGGER.error("Error initiating answer retrieval for request: {}", request, e);
            final String errorMessage = convert(Map.of("errorMessage", "An internal error occurred: " + e.getMessage()));
            return generateResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
        }
    }

    private boolean isValid(final String transactionId) {
        try {
            fromString(transactionId);
            return true;
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }

    private HttpResponseMessage generateResponse(final HttpRequestMessage<?> request, final HttpStatus status, final String message) {
        return request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(message)
                .build();
    }

    private static QueryAsyncResponse toQueryResponse(final GeneratedAnswer generatedAnswer, final List<ChunkedEntry> chunkedEntriesFromBlobContainer) {
        return new QueryAsyncResponse(generatedAnswer.getTransactionId(),
                generatedAnswer.getAnswerStatus(),
                generatedAnswer.getReason(),
                generatedAnswer.getUserQuery(),
                generatedAnswer.getLlmResponse(),
                generatedAnswer.getQueryPrompt(),
                chunkedEntriesFromBlobContainer,
                generatedAnswer.getResponseGenerationTime().toString(),
                generatedAnswer.getResponseGenerationDuration());
    }
}
