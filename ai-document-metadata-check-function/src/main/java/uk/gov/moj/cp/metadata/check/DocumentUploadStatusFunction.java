package uk.gov.moj.cp.metadata.check;

import static com.microsoft.azure.functions.HttpStatus.NOT_FOUND;
import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.lang.String.format;
import static java.time.OffsetDateTime.parse;
import static java.util.Objects.isNull;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.valueOf;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.UuidUtil.isValid;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

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
 * Azure Function to for answer generation.
 * Initiates Answer generation and returns unique transactionId.
 */
public class DocumentUploadStatusFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadStatusFunction.class);

    public static final String DEFAULT_URL_EXPIRY_MINUTES = "120";
    public static final String FILE_EXTENSION_PDF = "pdf";

    private final DocumentUploadService documentUploadService;

    public DocumentUploadStatusFunction() {
        this.documentUploadService = new DocumentUploadService();
    }

    public DocumentUploadStatusFunction(final DocumentUploadService documentUploadService) {
        this.documentUploadService = documentUploadService;
    }

    /**
     * HTTP-triggered function to check the document upload status.
     *
     * @param request           The HTTP request
     * @param documentReference The uri param
     * @param context           The execution context
     * @return HTTP response with status
     */
    @FunctionName("DocumentUploadStatus")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = HttpMethod.GET, authLevel = FUNCTION, route = "document-upload/{documentReference}")
            HttpRequestMessage<DocumentUploadRequest> request,
            @BindingName("documentReference") String documentReference,
            final ExecutionContext context) {

        try {
            if (!isValid(documentReference)) {
                final String errorMessage = format("Received invalid documentReference '%s'", documentReference);
                return generateResponse(request, HttpStatus.BAD_REQUEST, convert(new RequestErrored(errorMessage)));
            }

            final DocumentIngestionOutcome document = documentUploadService.getDocument(documentReference);

            if (isNull(document)) {
                return generateResponse(request, NOT_FOUND, convert(new RequestErrored(format("No Document found for the documentReference=%s", documentReference))));
            }

            LOGGER.info("Document with id {} has upload status {}", document.getDocumentId(), document.getStatus());
            return generateResponse(request, HttpStatus.OK, convert(toDocumentStatus(document)));
        } catch (Exception e) {
            LOGGER.error("Error getting the document upload status for documentReference: {}", documentReference, e);
            final String errorMessage = "An internal error occurred: " + e.getMessage();
            return generateResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, convert(new RequestErrored(errorMessage)));
        }
    }

    private DocumentIngestionStatusReturnedSuccessfully toDocumentStatus(final DocumentIngestionOutcome document) {
        return new DocumentIngestionStatusReturnedSuccessfully(document.getDocumentName(),
                document.getDocumentName(),
                valueOf(document.getStatus()),
                parse(document.getTimestamp()),
                document.getReason());
    }

    private HttpResponseMessage generateResponse(final HttpRequestMessage<?> request, final HttpStatus status,
                                                 final String message) {
        return request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(message)
                .build();
    }

}
