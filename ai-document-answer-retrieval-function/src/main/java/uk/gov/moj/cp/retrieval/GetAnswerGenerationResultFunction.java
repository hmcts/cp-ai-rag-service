package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.NOT_FOUND;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.model.QueryAsyncResponse;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

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

    private final ResponseGenerationService responseGenerationService;

    private final AnswerGenerationTableStorageService answerGenerationTableStorageService;

    public GetAnswerGenerationResultFunction() {
        responseGenerationService = new ResponseGenerationService();
        final String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION);
        answerGenerationTableStorageService = new AnswerGenerationTableStorageService(tableName);
    }

    public GetAnswerGenerationResultFunction(final ResponseGenerationService responseGenerationService, final AnswerGenerationTableStorageService answerGenerationTableStorageService) {
        this.responseGenerationService = responseGenerationService;
        this.answerGenerationTableStorageService = answerGenerationTableStorageService;
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
            @HttpTrigger(name = "req", methods = HttpMethod.GET, authLevel = FUNCTION, route = "answers/{transactionId}")
            HttpRequestMessage<Optional<String>> request,
            @BindingName("transactionId") String transactionId,
            final ExecutionContext context
    ) {

        try {

            if (isNullOrEmpty(transactionId)) {
                LOGGER.error("Error: transactionId is required");
                final String errorMessage = convertObjectToJson(Map.of("errorMessage", "Error: transactionId is required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            final GeneratedAnswer generatedAnswer = answerGenerationTableStorageService.getGeneratedAnswer(transactionId);

            if (nonNull(generatedAnswer)) {
                if (!ANSWER_GENERATION_PENDING.name().equals(generatedAnswer.getAnswerStatus())) {
                    String generatedResponse = null;
                    if (ANSWER_GENERATED.name().equals(generatedAnswer.getAnswerStatus())) {
                        generatedResponse = responseGenerationService.generateResponse(generatedAnswer.getUserQuery(), generatedAnswer.getChunkedEntries(), generatedAnswer.getQueryPrompt());
                    }

                    final QueryAsyncResponse queryResponse = toQueryResponse(generatedAnswer, generatedResponse);
                    return generateResponse(request, OK, convertObjectToJson(queryResponse));
                }
                return generateResponse(request, OK, convertObjectToJson(new QueryAsyncResponse(generatedAnswer.getTransactionId(), generatedAnswer.getAnswerStatus())));
            }

            return generateResponse(request, NOT_FOUND, String.format("No Answer request found for the transactionId=%s", transactionId));

        } catch (Exception e) {
            LOGGER.error("Error initiating answer retrieval for request: {}", request, e);
            final String errorMessage = convertObjectToJson(Map.of("errorMessage", "An internal error occurred: " + e.getMessage()));
            return generateResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
        }
    }

    private HttpResponseMessage generateResponse(final HttpRequestMessage<?> request, final HttpStatus status, final String message) {
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

    private static QueryAsyncResponse toQueryResponse(final GeneratedAnswer generatedAnswer, final String generatedResponse) {
        return new QueryAsyncResponse(generatedAnswer.getTransactionId(),
                generatedAnswer.getAnswerStatus(),
                generatedAnswer.getReason(),
                generatedAnswer.getUserQuery(),
                generatedResponse,
                generatedAnswer.getQueryPrompt(),
                generatedAnswer.getChunkedEntries(),
                generatedAnswer.getResponseGenerationTime().toString(),
                generatedAnswer.getResponseGenerationDuration());
    }
}
