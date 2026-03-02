package uk.gov.moj.cp.metadata.check;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.removeTrailingSlash;
import static uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver.getDocumentId;
import static uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer.stringToMap;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

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

    private final BlobClientService blobClientService;
    private final DocumentUploadService documentUploadService;

    public DocumentBlobTriggerFunction() {
        final String documentContainerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        this.blobClientService = new BlobClientService(documentContainerName);
        this.documentUploadService = new DocumentUploadService();
    }

    DocumentBlobTriggerFunction(final BlobClientService blobClientService, final DocumentUploadService documentUploadService) {
        this.blobClientService = blobClientService;
        this.documentUploadService = documentUploadService;
    }

    @FunctionName("DocumentUploadCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "%STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD%/{name}",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            )
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

            final String documentId = getDocumentId(blobName);
            final DocumentIngestionOutcome document = documentUploadService.getDocument(documentId);
            documentUploadService.updateDocumentAwaitingIngestion(document.getDocumentId(), document.getDocumentName());

            final Map<String, String> metadataMap = stringToMap(document.getMetadata());
            final QueueIngestionMetadata queueIngestionMetadata = createQueueMessage(blobName, flatten(documentId, metadataMap));
            queueMessage.setValue(getObjectMapper().writeValueAsString(queueIngestionMetadata));

            LOGGER.info("Document blob trigger function processed a request for document with blobName: {}", blobName);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize message and publish to queue for document '" + blobName + "'", e);
        }
    }

    private QueueIngestionMetadata createQueueMessage(final String blobName, final Map<String, String> metadata) {
        final String documentId = metadata.get(DOCUMENT_ID);
        final String blobStorageEndpoint = removeTrailingSlash(getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT));
        final String containerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        final String blobUrl = format("%s/%s/%s", blobStorageEndpoint, containerName, blobName);
        final String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(documentId, blobName, metadata, blobUrl, currentTimestamp);
    }

    private static Map<String, String> flatten(final String documentId, final Map<String, String> metadataMap) {
        final Map<String, String> result = new LinkedHashMap<>();

        result.put(DOCUMENT_ID, documentId);
        if (nonNull(metadataMap)) {
            result.putAll(metadataMap);
        }
        return result;
    }
}


