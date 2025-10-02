package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.retrieval.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.model.RequestPayload;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;
import uk.gov.moj.cp.retrieval.service.SearchService;

import java.util.List;
import java.util.Map;

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
 * Azure Function for answer retrieval and generation.
 * Processes client queries, performs retrieval/grounding, and generates answer summaries.
 */
public class AnswerRetrievalFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerRetrievalFunction.class);

    private final EmbedDataService embedDataService;

    private final SearchService searchService;

    private final ResponseGenerationService responseGenerationService;

    public AnswerRetrievalFunction() {
        embedDataService = new EmbedDataService();
        searchService = new SearchService();
        responseGenerationService = new ResponseGenerationService();
    }

    public AnswerRetrievalFunction(final EmbedDataService embedDataService, final SearchService searchService, final ResponseGenerationService responseGenerationService) {
        this.embedDataService = embedDataService;
        this.searchService = searchService;
        this.responseGenerationService = responseGenerationService;
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
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION) HttpRequestMessage<RequestPayload> request,
            @QueueOutput(name = "message", queueName = "%STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING%", connection = "AI_RAG_SERVICE_STORAGE_ACCOUNT") OutputBinding<String> message,
            final ExecutionContext context) {

        LOGGER.info("Initiating answer generation process for query - {}", request.getBody().userQuery());

        try {
            // Extract query from request
            final String userQuery = request.getBody().userQuery();
            final String userQueryPrompt = request.getBody().queryPrompt();
            final List<KeyValuePair> metadataFilters = request.getBody().metadataFilter();

            if (isNullOrEmpty(userQuery) || isNullOrEmpty(userQueryPrompt) || metadataFilters == null || metadataFilters.isEmpty()) {
                LOGGER.error("Invalid query: {}", userQuery);
                final String errorMessage = convertObjectToJson(Map.of("errorMessage", "Error: userQuery, queryPrompt and metadataFilter attributes are required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            // - Process the user query
            final List<Double> queryEmbeddings = embedDataService.getEmbedding(userQuery);

            final List<ChunkedEntry> chunkedEntries = searchService.searchDocumentsMatchingFilterCriteria(userQuery, queryEmbeddings, metadataFilters);

            final String generatedResponse = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

            LOGGER.info("Answer retrieval processing completed successfully for query: {}", userQuery);

            final QueryResponse queryResponse = new QueryResponse(userQuery, generatedResponse, userQueryPrompt, chunkedEntries);

            final String responseAsString = convertObjectToJson(queryResponse);

            message.setValue(responseAsString);

            return generateResponse(request, HttpStatus.OK, responseAsString);

        } catch (Exception e) {
            LOGGER.error("Error processing answer retrieval for request: {}", request, e);
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
}
