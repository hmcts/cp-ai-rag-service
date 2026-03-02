package uk.gov.moj.cp.metadata.check;

import static java.lang.String.format;
import static java.util.Objects.nonNull;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.AWAITING_UPLOAD;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
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
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

import java.time.Instant;
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
    private final DocumentUploadService documentUploadService;


    public DocumentBlobTriggerFunction() {
        this.documentUploadService = new DocumentUploadService();
    }

    DocumentBlobTriggerFunction(final DocumentUploadService documentUploadService) {
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
            final String documentId = getDocumentId(blobName);
            final DocumentIngestionOutcome document = documentUploadService.getDocument(documentId);

            if (nonNull(document)
                    && nonNull(document.getStatus())
                    && !document.getStatus().equals(AWAITING_UPLOAD.name())) {
                LOGGER.info("Document '{}' is already uploaded and has status '{}'.", documentId, document.getStatus());
                return;
            }
            documentUploadService.updateDocumentAwaitingIngestion(document.getDocumentId(), document.getDocumentName());

            final QueueIngestionMetadata queueIngestionMetadata = createQueueMessage(blobName, stringToMap(document.getMetadata()));
            queueMessage.setValue(getObjectMapper().writeValueAsString(queueIngestionMetadata));

            LOGGER.info("Document blob trigger function processed a request for document with blobName: {}", blobName);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize message and publish to queue for document '" + blobName + "'", e);
        }
    }

    private QueueIngestionMetadata createQueueMessage(String blobName, Map<String, String> metadata) {
        final String documentId = metadata.get(DOCUMENT_ID);
        final String blobStorageEndpoint = removeTrailingSlash(getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT));
        final String containerName = getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD);
        final String blobUrl = format("%s/%s/%s", blobStorageEndpoint, containerName, blobName);
        final String currentTimestamp = Instant.now().toString();

        return new QueueIngestionMetadata(documentId, blobName, metadata, blobUrl, currentTimestamp);
    }
}


