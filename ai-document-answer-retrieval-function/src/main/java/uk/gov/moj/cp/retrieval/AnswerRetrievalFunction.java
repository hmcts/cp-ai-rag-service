package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.UUID.randomUUID;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.ai.langfuse.LangfuseConfig;
import uk.gov.moj.cp.retrieval.model.RequestPayload;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for answer retrieval and generation. Processes client queries and  generates
 * answer summaries.
 */
public class AnswerRetrievalFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerRetrievalFunction.class);

    private final EmbedDataService embedDataService;

    private final AzureAISearchService searchService;

    private final ResponseGenerationService responseGenerationService;

    private final BlobPersistenceService blobPersistenceService;

    public AnswerRetrievalFunction() {
        embedDataService = new EmbedDataService();
        searchService = new AzureAISearchService();
        responseGenerationService = new ResponseGenerationService();
        blobPersistenceService = new BlobPersistenceService();
    }

    public AnswerRetrievalFunction(final EmbedDataService embedDataService, final AzureAISearchService searchService, final ResponseGenerationService responseGenerationService, final BlobPersistenceService blobPersistenceService) {
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
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION) HttpRequestMessage<RequestPayload> request,
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        try {
            final String userQuery = request.getBody().userQuery();
            final String userQueryPrompt = request.getBody().queryPrompt();
            final List<KeyValuePair> metadataFilters = request.getBody().metadataFilter();

            if (isNullOrEmpty(userQuery) || isNullOrEmpty(userQueryPrompt) || metadataFilters == null || metadataFilters.isEmpty()) {
                LOGGER.error("Error: userQuery, queryPrompt and metadataFilter attributes are required");
                final String errorMessage = convertObjectToJson(Map.of("errorMessage", "Error: userQuery, queryPrompt and metadataFilter attributes are required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }
            LOGGER.info("Initiating answer generation process for query - {}", userQuery);

            Tracer tracer = LangfuseConfig.getTracer();
            Span rootSpan = tracer.spanBuilder("rag-answer-generation").startSpan();
            rootSpan.setAttribute("input.value", userQuery);

            try (Scope scope = rootSpan.makeCurrent()) {
                final List<Float> queryEmbeddings = embedDataService.getEmbedding(userQuery);

                Span retrievalSpan = tracer.spanBuilder("retrieval").startSpan();
                final List<ChunkedEntry> chunkedEntries = searchService.search(userQuery, queryEmbeddings, metadataFilters);
                retrievalSpan.setAttribute("db.query.text", userQuery);
                retrievalSpan.setAttribute("langfuse.observation.metadata.retrieval.output", chunkedEntries.stream()
                        .map(ChunkedEntry::toString)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(""));
                retrievalSpan.end();

                final String generatedResponse = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);
                rootSpan.setAttribute("output.value", generatedResponse);

                LOGGER.info("Answer retrieval processing completed successfully for query: {}", userQuery);

                final QueryResponse queryResponse = new QueryResponse(userQuery, generatedResponse, userQueryPrompt, chunkedEntries);

                final String responseAsString = convertObjectToJson(queryResponse);

                final String filename = "llm-answer-with-chunks-" + randomUUID() + ".json";
                blobPersistenceService.saveBlob(
                        filename,
                        getObjectMapper().writeValueAsString(
                                new ScoringPayload(
                                        userQuery,
                                        generatedResponse,
                                        userQueryPrompt,
                                        chunkedEntries,
                                        null
                                )
                        )
                );

                ScoringQueuePayload scoringQueuePayload = new ScoringQueuePayload(filename);
                message.setValue(convertObjectToJson(scoringQueuePayload));

                return generateResponse(request, HttpStatus.OK, responseAsString);
            } finally {
                rootSpan.end();
            }


        } catch (Exception e) {
            LOGGER.error("Error processing answer retrieval for request: {}", request, e);
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