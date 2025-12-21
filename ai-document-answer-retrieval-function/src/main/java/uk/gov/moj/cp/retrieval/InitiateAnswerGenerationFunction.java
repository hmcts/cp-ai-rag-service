package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.isNull;
import static java.util.UUID.randomUUID;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.model.QueryAsyncResponse;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus;
import uk.gov.moj.cp.retrieval.model.RequestPayload;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Azure Function to for answer generation.
 * Initiates Answer generation and returns unique transactionId.
 */
public class InitiateAnswerGenerationFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitiateAnswerGenerationFunction.class);

    private final AnswerGenerationTableStorageService answerGenerationTableStorageService;

    public InitiateAnswerGenerationFunction() {
        final String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION);
        answerGenerationTableStorageService = new AnswerGenerationTableStorageService(tableName);
    }

    public InitiateAnswerGenerationFunction(final AnswerGenerationTableStorageService answerGenerationTableStorageService) {
        this.answerGenerationTableStorageService = answerGenerationTableStorageService;
    }

    /**
     * HTTP-triggered function to initiate answer generation.
     *
     * @param request The HTTP request containing the query
     * @param context The execution context
     * @return HTTP response with transactionId
     */
    @FunctionName("InitiateAnswerGeneration")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION) HttpRequestMessage<RequestPayload> request,
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        try {
            final String userQuery = request.getBody().userQuery();
            final String userQueryPrompt = request.getBody().queryPrompt();
            final List<KeyValuePair> metadataFilters = request.getBody().metadataFilter();

            if (isNullOrEmpty(userQuery) || isNullOrEmpty(userQueryPrompt) || isNull(metadataFilters) || metadataFilters.isEmpty()) {
                LOGGER.error("Error: userQuery, queryPrompt and metadataFilter attributes are required");
                final String errorMessage = convertObjectToJson(Map.of("errorMessage", "Error: userQuery, queryPrompt and metadataFilter attributes are required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            final UUID transactionId = randomUUID();
            LOGGER.info("Initiating answer generation async process for the query: {} with transactionId: {}", userQuery, transactionId);

            // persist payload in Queue
            final AnswerGenerationQueuePayload answerGenerationQueuePayload = new AnswerGenerationQueuePayload(transactionId, userQuery, userQueryPrompt, metadataFilters);
            message.setValue(convertObjectToJson(answerGenerationQueuePayload));

            // Persist status as ANSWER_GENERATION_PENDING in new Table  against the TransactionID
            answerGenerationTableStorageService.saveAnswerGenerationRequest(transactionId.toString(), userQuery, userQueryPrompt, ANSWER_GENERATION_PENDING);

            LOGGER.info("Successfully initiated answer retrieval processing for the query: {} with transactionId: {}", userQuery, transactionId);

            final String responseAsString = convertObjectToJson(new QueryAsyncResponse(transactionId.toString(), ANSWER_GENERATION_PENDING.name()));
            return generateResponse(request, HttpStatus.OK, responseAsString);

        } catch (Exception e) {
            LOGGER.error("Error initiating answer retrieval for request: {}", request, e);
            final String errorMessage = convertObjectToJson(Map.of("errorMessage", "An internal error occurred: " + e.getMessage()));
            return generateResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
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

    private String convertObjectToJson(final Object object) {
        try {
            return getObjectMapper().writeValueAsString(object);
        } catch (Exception e) {
            LOGGER.error("Error converting object to JSON", e);
            return "{}";
        }
    }
}
