package uk.gov.moj.cp.azure.status.check;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static com.microsoft.azure.functions.HttpStatus.NOT_FOUND;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus;
import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentStatusNotAvailable;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import java.time.OffsetDateTime;
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
 * Azure Function for answer retrieval and generation. Processes client queries and  generates
 * answer summaries.
 */
public class DocumentStatusCheckFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStatusCheckFunction.class);
    private static final String RESPONSE_CONTENT_TYPE_HEADER = "Content-Type";
    private static final String RESPONSE_CONTENT_TYPE_VALUE = "application/json";
    private static final String QUERY_PARAM_DOCUMENT_NAME = "document-name";

    private final DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    public DocumentStatusCheckFunction() {
        String tableName = System.getenv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME);
        this.documentIngestionOutcomeTableService = new DocumentIngestionOutcomeTableService(tableName);
    }

    public DocumentStatusCheckFunction(DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService) {
        this.documentIngestionOutcomeTableService = documentIngestionOutcomeTableService;
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

        String documentName = request.getQueryParameters().get(QUERY_PARAM_DOCUMENT_NAME);
        if (isNullOrEmpty(documentName)) {
            LOGGER.error("Missing required parameter: document-name");
            return request.createResponseBuilder(BAD_REQUEST)
                    .header(RESPONSE_CONTENT_TYPE_HEADER, RESPONSE_CONTENT_TYPE_VALUE)
                    .body(convert(new DocumentStatusNotAvailable("N/A", "Missing required query parameter: document-name")))
                    .build();
        }

        LOGGER.info("Retrieving status for document name: {}", documentName);

        try {
            final DocumentIngestionOutcome firstDocumentMatching = documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName);
            if (nonNull(firstDocumentMatching)) {
                return request.createResponseBuilder(OK)
                        .body(convert(generateResponse(firstDocumentMatching)))
                        .header(RESPONSE_CONTENT_TYPE_HEADER, RESPONSE_CONTENT_TYPE_VALUE)
                        .build();
            }

            return request.createResponseBuilder(NOT_FOUND)
                    .header(RESPONSE_CONTENT_TYPE_HEADER, RESPONSE_CONTENT_TYPE_VALUE)
                    .body(convert(new DocumentStatusNotAvailable(documentName, "Unknown file with name")))
                    .build();

        } catch (EntityRetrievalException e) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(RESPONSE_CONTENT_TYPE_HEADER, RESPONSE_CONTENT_TYPE_VALUE)
                    .body(convert(new DocumentStatusNotAvailable(documentName, "Error retrieving status for document name")))
                    .build();
        }


    }

    private DocumentIngestionStatusReturnedSuccessfully generateResponse(final DocumentIngestionOutcome firstDocumentMatching) {
        return new DocumentIngestionStatusReturnedSuccessfully(
                firstDocumentMatching.getDocumentName(),
                firstDocumentMatching.getDocumentId(),
                DocumentIngestionStatus.valueOf(firstDocumentMatching.getStatus()),
                OffsetDateTime.parse(firstDocumentMatching.getTimestamp()),
                firstDocumentMatching.getReason()
        );
    }
}
