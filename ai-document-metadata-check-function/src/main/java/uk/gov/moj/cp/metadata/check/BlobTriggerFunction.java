package uk.gov.moj.cp.metadata.check;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.model.QueueTaskResult;
import uk.gov.moj.cp.metadata.check.service.BlobMetadataService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.StorageAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobTriggerFunction {
    
    private static final Logger logger = LoggerFactory.getLogger(BlobTriggerFunction.class);
    private final BlobMetadataService blobMetadataService;
    private final QueueStorageService queueStorageService;

    public BlobTriggerFunction() {
        String storageConnectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
        String documentIngestionQueue = System.getenv("DOCUMENT_INGESTION_QUEUE");
        String documentIngestionOutcomeTable = System.getenv("DOCUMENT_INGESTION_OUTCOME_TABLE");
        String documentContainerName = System.getenv("DOCUMENT_CONTAINER_NAME");

        this.blobMetadataService = new BlobMetadataService(storageConnectionString,documentContainerName,documentIngestionOutcomeTable);
        this.queueStorageService = new QueueStorageService(storageConnectionString, documentIngestionQueue, documentIngestionOutcomeTable);
    }

    BlobTriggerFunction(BlobMetadataService blobMetadataService, QueueStorageService queueStorageService) {
        this.blobMetadataService = blobMetadataService;
        this.queueStorageService = queueStorageService;
    }

    @FunctionName("DocumentMetadataCheck")
    public void run(
            @BlobTrigger(
                name = "blob",
                path = "documents/{name}",
                connection = "AzureWebJobsStorage"
            )
            @BindingName("name") String documentName,
            final ExecutionContext context) {
        
        logger.info("Blob trigger function processed a request for blob: {}", documentName);
        logger.info("Function execution ID: {}", context.getInvocationId());

        logger.info("Blob trigger function processed a request for {}", documentName);
        logger.info("Function execution ID: {}", context.getInvocationId());


        Map<String, String> blobMetadata = blobMetadataService.processBlobMetadata(documentName);

        QueueIngestionMetadata queueMessage = queueStorageService.createQueueMessage(documentName, blobMetadata);

        QueueTaskResult queueTaskResult = queueStorageService.sendToQueue(queueMessage);

        if (queueTaskResult.success()) {
            logger.info("Document {} successfully processed and queued", documentName);
        } else {
            logger.error("Failed to queue {} {}", documentName, queueTaskResult.errorMessage());
        }
    }
}
