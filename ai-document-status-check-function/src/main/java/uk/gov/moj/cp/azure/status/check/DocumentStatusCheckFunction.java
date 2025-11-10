package uk.gov.moj.cp.azure.status.check;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.azure.status.check.model.DocumentStatusRetrievedResponse;
import uk.gov.moj.cp.azure.status.check.model.DocumentUnknownResponse;

import java.util.Optional;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for answer retrieval and generation.
 * Processes client queries and  generates answer summaries.
 */
public class DocumentStatusCheckFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStatusCheckFunction.class);

    private final TableStorageService tableStorageService;

    public DocumentStatusCheckFunction() {
        String endpoint = System.getenv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT);
        String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);
        this.tableStorageService = new TableStorageService(endpoint, tableName);
    }

    public DocumentStatusCheckFunction(TableStorageService tableStorageService) {
        this.tableStorageService = tableStorageService;
    }


    /**
     * HTTP-triggered function for retrieving document upload status.
     *
     * @param request The HTTP request containing the query
     * @param context The execution context
     * @return HTTP response with the generated answer
     */
    @FunctionName("DocumentStatusCheck")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.GET}, authLevel = FUNCTION)
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        String documentName = request.getQueryParameters().get("document-name");
        validateNullOrEmpty(documentName, "Query parameter `document-name` is required and cannot be null or empty.");

        LOGGER.info("Retrieving status for document name: {}", documentName);

        final DocumentIngestionOutcome firstDocumentMatching = tableStorageService.getFirstDocumentMatching(documentName);

        if (nonNull(firstDocumentMatching)) {
            return request.createResponseBuilder(HttpStatus.OK)
                    .body(generateResponse(firstDocumentMatching))
                    .header("Content-Type", "application/json")
                    .build();
        }

        return request.createResponseBuilder(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/json")
                .body(new DocumentUnknownResponse(documentName, "Unknown file with name"))
                .build();
    }

    private DocumentStatusRetrievedResponse generateResponse(final DocumentIngestionOutcome firstDocumentMatching) {
        return new DocumentStatusRetrievedResponse(
                firstDocumentMatching.getDocumentId(),
                firstDocumentMatching.getDocumentName(),
                firstDocumentMatching.getStatus(),
                firstDocumentMatching.getReason(),
                firstDocumentMatching.getTimestamp());
    }
}
