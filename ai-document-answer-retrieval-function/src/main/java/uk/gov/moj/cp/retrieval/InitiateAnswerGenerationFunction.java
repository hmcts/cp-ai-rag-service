package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static com.microsoft.azure.functions.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.microsoft.azure.functions.HttpStatus.ACCEPTED;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.lang.String.join;
import static java.util.UUID.randomUUID;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.validation.MetadataFilterValidator.validateReservedKeys;
import static uk.gov.moj.cp.ai.validation.RequestValidator.validate;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerRequestAccepted;
import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.client.identity.HeaderClientIdentityResolver;
import uk.gov.moj.cp.ai.http.HttpResponses;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;

import java.util.ArrayList;
import java.util.List;
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
 * Azure Function to for answer generation. Initiates Answer generation and returns unique
 * transactionId.
 */
public class InitiateAnswerGenerationFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitiateAnswerGenerationFunction.class);

    private final AnswerGenerationTableService answerGenerationTableService;
    private final ClientIdentityResolver clientIdentityResolver;

    public InitiateAnswerGenerationFunction() {
        final String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION);
        answerGenerationTableService = new AnswerGenerationTableService(tableName);
        clientIdentityResolver = HeaderClientIdentityResolver.fromEnvironment();
    }

    public InitiateAnswerGenerationFunction(final AnswerGenerationTableService answerGenerationTableService,
                                            final ClientIdentityResolver clientIdentityResolver) {
        this.answerGenerationTableService = answerGenerationTableService;
        this.clientIdentityResolver = clientIdentityResolver != null
                ? clientIdentityResolver : HeaderClientIdentityResolver.fromEnvironment();
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
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION, route = "answer-user-query-async") HttpRequestMessage<AnswerUserQueryRequest> request,
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        final ClientContext clientContext;
        try {
            // Enforcement on: reject a missing/invalid client identity before enqueuing or persisting.
            // Flag off: an empty context, so the payload / pending row stay legacy null-scoped.
            clientContext = clientIdentityResolver.resolve(request);
        } catch (ClientIdentityException e) {
            return HttpResponses.unauthorized(request);
        }
        final String clientId = clientContext.clientId().orElse(null);

        try {
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

            final UUID transactionId = randomUUID();
            LOGGER.info("Initiating answer generation async process for the query: {} with transactionId: {}", userQuery, transactionId);

            final AnswerGenerationQueuePayload answerGenerationQueuePayload = new AnswerGenerationQueuePayload(transactionId, userQuery, userQueryPrompt, metadataFilters, clientId);
            message.setValue(convert(answerGenerationQueuePayload));

            answerGenerationTableService.saveAnswerGenerationRequest(clientId, transactionId.toString(), userQuery, userQueryPrompt, ANSWER_GENERATION_PENDING);
            LOGGER.info("Successfully initiated answer retrieval processing for the query: {} with transactionId: {}", userQuery, transactionId);

            return generateResponse(request, ACCEPTED, convert(new UserQueryAnswerRequestAccepted(transactionId.toString())));

        } catch (Exception e) {
            LOGGER.error("Error initiating answer retrieval for request: {}", request, e);
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
