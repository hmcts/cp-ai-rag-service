package uk.gov.moj.cp.metadata.check;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer.stringToMap;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentBlobTriggerFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentBlobTriggerFunction.class);
    private static final String DOCUMENT_ID_NEW = "documentId";
    private static final String DOCUMENT_ID = "document_id";

    private final BlobClientService blobClientService;
    private final DocumentUploadService documentUploadService;
    private final DocumentBlobNameResolver documentBlobNameResolver;

    public DocumentBlobTriggerFunction() {
        final String documentContainerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        this.blobClientService = new BlobClientService(documentContainerName);
        this.documentUploadService = new DocumentUploadService();
        this.documentBlobNameResolver = new DocumentBlobNameResolver();
    }

    DocumentBlobTriggerFunction(final BlobClientService blobClientService,
                                final DocumentUploadService documentUploadService,
                                final DocumentBlobNameResolver documentBlobNameResolver) {
        this.blobClientService = blobClientService;
        this.documentUploadService = documentUploadService;
        this.documentBlobNameResolver = documentBlobNameResolver;
    }

    @FunctionName("DocumentUploadCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "%STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD%/{name}",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) byte[] content,
            @BindingName("name") String blobName,
            @QueueOutput(name = "queueMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING)
            OutputBinding<String> queueMessage) {
        try {
            if (!blobClientService.isBlobAvailable(blobName)) {
                LOGGER.info("Blob container is not available for blobName: {}.", blobName);
                return;
            }

            final String documentId = documentBlobNameResolver.getDocumentId(blobName);
            final DocumentIngestionOutcome document = documentUploadService.getDocument(documentId);
            documentUploadService.updateDocumentAwaitingIngestion(document.getDocumentId());

            final Map<String, String> metadataMap = stringToMap(document.getMetadata());
            final QueueIngestionMetadata queueIngestionMetadata = createQueueMessage(blobName, document.getDocumentName(), flatten(documentId, metadataMap));
            queueMessage.setValue(convert(queueIngestionMetadata));

            LOGGER.info("Document blob trigger function processed a request for document with blobName: {}", blobName);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize message and publish to queue for document '" + blobName + "'", e);
        }
    }

    private QueueIngestionMetadata createQueueMessage(final String blobName, final String documentName, final Map<String, String> metadata) {
        final String documentId = metadata.get(DOCUMENT_ID_NEW);
        final String blobStorageEndpoint = removeTrailingSlash(getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT));
        final String containerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        final String blobUrl = format("%s/%s/%s", blobStorageEndpoint, containerName, blobName);
        final String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(documentId, documentName, metadata, blobUrl, currentTimestamp, true);
    }

    private static Map<String, String> flatten(final String documentId, final Map<String, String> metadataMap) {
        final Map<String, String> result = new LinkedHashMap<>();

        //for backwards compatibility
        result.put(DOCUMENT_ID, documentId);
        //new approach going forward
        result.put(DOCUMENT_ID_NEW, documentId);
        if (nonNull(metadataMap)) {
            result.putAll(metadataMap);
        }
        return result;
    }
}