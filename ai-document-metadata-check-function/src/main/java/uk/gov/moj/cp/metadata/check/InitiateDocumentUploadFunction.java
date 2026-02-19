package uk.gov.moj.cp.metadata.check;

import static com.microsoft.azure.functions.annotation.AuthorizationLevel.FUNCTION;
import static java.util.Objects.isNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.model.BlobMetadataAttributes.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.UuidUtil.isValid;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.FileStorageLocationReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;
import uk.gov.moj.cp.metadata.check.service.StorageService;

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
 * Azure Function to for answer generation.
 * Initiates Answer generation and returns unique transactionId.
 */
public class InitiateDocumentUploadFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitiateDocumentUploadFunction.class);

    private final DocumentMetadataService documentMetadataService;
    private final IngestionOrchestratorService orchestratorService;
    private final StorageService storageService;

    private static final String RESPONSE_STR = "{\"documentReference\":\"%s\"}";

    public InitiateDocumentUploadFunction() {
        this.documentMetadataService = new DocumentMetadataService();
        this.orchestratorService = new IngestionOrchestratorService(documentMetadataService);
        this.storageService = new StorageService();
    }

    public InitiateDocumentUploadFunction(final DocumentMetadataService documentMetadataService,
                                          final IngestionOrchestratorService orchestratorService,
                                          final StorageService storageService) {
        this.documentMetadataService = documentMetadataService;
        this.orchestratorService = orchestratorService;
        this.storageService = storageService;
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
            @QueueOutput(name = "message", queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) OutputBinding<String> message,
            final ExecutionContext context) {

        try {
            final String documentId = request.getBody().getDocumentId();
            final String documentName = request.getBody().getDocumentName();
            final List<MetadataFilter> metadataFilters = request.getBody().getMetadataFilter();

            if (isNull(documentId) || !isValid(documentId) || isNullOrEmpty(documentName) || isNull(metadataFilters) || metadataFilters.isEmpty()) {
                LOGGER.error("Error: documentId, documentName and metadataFilter attributes are required");
                final String errorMessage = convert(Map.of("errorMessage", "Error: documentId, documentName and metadataFilter attributes are required"));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            LOGGER.info("Initiating document upload for the documentId: {} documentName: {}", documentId, documentName);

            //check documentId not already processed
            if (orchestratorService.isDocumentAlreadyProcessed(documentName)) {
                final String errorMessage = convert(Map.of("errorMessage", "An upload request has already been initiated for documentId: " + documentId));
                return generateResponse(request, HttpStatus.BAD_REQUEST, errorMessage);
            }

            //validate metadata
            final Map<String, String> metadata = documentMetadataService.processDocumentMetadata(documentName);
            LOGGER.info("Metadata for document '{}' with ID '{}' validated successfully", documentName, metadata.get(DOCUMENT_ID));

            //generate storageUrl
            final String storageUrl = storageService.getSasUrl(documentName);

            //save record in documentsoutcome table with metadata validated status
            orchestratorService.recordUploadInitiated(documentName, documentId);

            //return storageUrl and documentReference (documentId) to client
            final FileStorageLocationReturnedSuccessfully fileStorageLocationReturnedSuccessfully = new FileStorageLocationReturnedSuccessfully(storageUrl, documentId);

            LOGGER.info("Successfully initiated document upload for the documentId: {} documentName: {}", documentId, documentName);

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

}
