package uk.gov.moj.cp.metadata.check;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.UuidUtil.isValid;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

import java.util.List;
import java.util.Map;

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
 * Azure Function to for answer generation.
 * Initiates Answer generation and returns unique transactionId.
 */
public class InitiateDocumentUploadFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitiateDocumentUploadFunction.class);

    private final DocumentUploadService documentUploadService;
    private final BlobClientService blobClientService;

    public InitiateDocumentUploadFunction() {
        final String documentContainerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME);
        this.blobClientService = new BlobClientService(documentContainerName);
        this.documentUploadService = new DocumentUploadService();
    }

    public InitiateDocumentUploadFunction(final BlobClientService blobClientService,
                                          final DocumentUploadService documentUploadService) {
        this.blobClientService = blobClientService;
        this.documentUploadService = documentUploadService;
    }

    /**
     * HTTP-triggered function to initiate document upload.
     *
     * @param request The HTTP request containing the query
     * @param context The execution context
     * @return HTTP response with transactionId
     */
    @FunctionName("InitiateDocumentUpload")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = FUNCTION, route = "document-upload") HttpRequestMessage<DocumentUploadRequest> request,
            final ExecutionContext context) {

        try {
            final String documentId = request.getBody().getDocumentId();
            final String documentName = request.getBody().getDocumentName();
            final List<MetadataFilter> metadataFilters = request.getBody().getMetadataFilter();

            if (isNull(documentId) || !isValid(documentId) || isNullOrEmpty(documentName) || isValidMetadata(metadataFilters)) {
                LOGGER.error("Error: documentId, documentName and metadataFilter attributes are required");
                final String errorMessage = convert(Map.of("errorMessage", "Error: documentId, documentName and metadataFilter attributes are required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            LOGGER.info("Initiating document upload for the documentId: {} documentName: {}", documentId, documentName);

            //check documentId not already processed
            if (documentUploadService.isDocumentAlreadyProcessed(documentId)) {
                final String errorMessage = convert(Map.of("errorMessage", "An upload request has already been initiated for documentId: " + documentId));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            //generate storageUrl
            final String storageUrl = blobClientService.getSasUrl(documentName);

            //save record in documentsoutcome table with metadata validated status
            documentUploadService.recordUploadInitiated(documentName, documentId);

            LOGGER.info("Successfully initiated document upload for the documentId: {} documentName: {}", documentId, documentName);
            final FileStorageLocationReturnedSuccessfully fileStorageLocationReturnedSuccessfully = new FileStorageLocationReturnedSuccessfully(storageUrl, documentId);
            return generateResponse(request, HttpStatus.OK, convert(fileStorageLocationReturnedSuccessfully));

        } catch (Exception e) {
            LOGGER.error("Error initiating document upload for request: {}", request, e);
            final String errorMessage = convert(Map.of("errorMessage", "An internal error occurred: " + e.getMessage()));
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

    private boolean isValidMetadata(final List<MetadataFilter> metadataFilters) {
        return nonNull(metadataFilters) && !metadataFilters.isEmpty()
                && metadataFilters.stream().noneMatch(mf -> isNullOrEmpty(mf.getKey()) || isNullOrEmpty(mf.getValue()));
    }

}
