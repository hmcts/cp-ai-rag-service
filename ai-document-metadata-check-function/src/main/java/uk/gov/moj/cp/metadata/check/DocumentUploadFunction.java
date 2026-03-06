package uk.gov.moj.cp.metadata.check;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.String.join;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.validation.RequestValidator.validate;
import static uk.gov.moj.cp.metadata.check.service.DocumentMetadataVariables.SAS_STORAGE_URL_EXPIRY_MINUTES;
import static uk.gov.moj.cp.metadata.check.service.DocumentMetadataVariables.UPLOAD_FILE_EXTENSION;
import static uk.gov.moj.cp.metadata.check.service.DocumentUploadService.DUPLICATE_RECORD_ERROR;
import static uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer.listToMap;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.RequestErrored;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import java.util.List;

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
public class DocumentUploadFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentUploadFunction.class);

    public static final String DEFAULT_URL_EXPIRY_MINUTES = "120";
    public static final String FILE_EXTENSION_PDF = "pdf";

    private final DocumentUploadService documentUploadService;
    private final BlobClientService blobClientService;
    private final DocumentBlobNameResolver documentBlobNameResolver;

    private final int urlExpiryMinutes;
    private final String uploadFileExtension;

    public DocumentUploadFunction() {
        final String documentContainerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        this.urlExpiryMinutes = getRequiredEnvAsInteger(SAS_STORAGE_URL_EXPIRY_MINUTES, DEFAULT_URL_EXPIRY_MINUTES);
        this.uploadFileExtension = getRequiredEnv(UPLOAD_FILE_EXTENSION, FILE_EXTENSION_PDF);

        this.blobClientService = new BlobClientService(documentContainerName);
        this.documentUploadService = new DocumentUploadService();
        this.documentBlobNameResolver = new DocumentBlobNameResolver();
    }

    public DocumentUploadFunction(final BlobClientService blobClientService,
                                  final DocumentUploadService documentUploadService,
                                  final DocumentBlobNameResolver documentBlobNameResolver) {
        this.urlExpiryMinutes = parseInt(DEFAULT_URL_EXPIRY_MINUTES);
        this.uploadFileExtension = FILE_EXTENSION_PDF;

        this.blobClientService = blobClientService;
        this.documentUploadService = documentUploadService;
        this.documentBlobNameResolver = documentBlobNameResolver;
    }

    /**
     * HTTP-triggered function to initiate document upload.
     *
     * @param request The HTTP request containing the query
     * @param context The execution context
     * @return HTTP response with storageUrl & documentReference
     */
    @FunctionName("InitiateDocumentUpload")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {HttpMethod.POST},
                    authLevel = FUNCTION, route = "document-upload") HttpRequestMessage<DocumentUploadRequest> request,
            final ExecutionContext context) {

        try {
            final DocumentUploadRequest documentUploadRequest = request.getBody();
            final List<String> errors = validate(documentUploadRequest);
            if (!errors.isEmpty()) {
                final String errorMessage = join(", ", errors);
                return generateResponse(request, HttpStatus.BAD_REQUEST, convert(new RequestErrored(errorMessage)));
            }

            final String documentId = documentUploadRequest.getDocumentId();
            final String documentName = documentUploadRequest.getDocumentName();
            LOGGER.info("Initiating document upload for the documentId: {} documentName: {}", documentId, documentName);

            if (documentUploadService.isDocumentAlreadyProcessed(documentId)) {
                final String errorMessage = "An upload request has already been initiated for documentId: " + documentId;
                return generateResponse(request, HttpStatus.BAD_REQUEST, convert(new RequestErrored(errorMessage)));
            }

            final String blobName = documentBlobNameResolver.getBlobName(documentId, uploadFileExtension);
            final String storageSasUrl = blobClientService.getSasUrl(blobName, urlExpiryMinutes);

            documentUploadService.addDocumentAwaitingUpload(documentId, documentName, listToMap(documentUploadRequest.getMetadataFilter()));

            LOGGER.info("Successfully initiated document upload for the documentId: {} documentName: {}", documentId, documentName);
            final FileStorageLocationReturnedSuccessfully fileStorageLocationReturnedSuccessfully = new FileStorageLocationReturnedSuccessfully(storageSasUrl, documentId);
            return generateResponse(request, HttpStatus.OK, convert(fileStorageLocationReturnedSuccessfully));

        } catch (DuplicateRecordException dre) {
            final String duplicateRecordError = format(DUPLICATE_RECORD_ERROR, request.getBody().getDocumentId());
            return generateResponse(request, HttpStatus.BAD_REQUEST, convert(new RequestErrored(duplicateRecordError)));
        } catch (Exception e) {
            LOGGER.error("Error initiating document upload for request: {}", request, e);
            final String errorMessage = "An internal error occurred: " + e.getMessage();
            return generateResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, convert(new RequestErrored(errorMessage)));
        }
    }

    private HttpResponseMessage generateResponse(final HttpRequestMessage<?> request, final HttpStatus status,
                                                 final String message) {
        return request.createResponseBuilder(status)
                .header("Content-Type", "application/json")
                .body(message)
                .build();
    }

}
