package uk.gov.moj.cp.metadata.check;

import static uk.gov.moj.cp.metadata.check.config.Config.getContainerName;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.model.QueueTaskResult;
import uk.gov.moj.cp.metadata.check.service.BlobMetadataService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import java.util.Map;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function triggered by blob storage events.
 * This function extracts/validates metadata when a new blob is uploaded and puts the message in a storage queue.
 */
public class BlobTriggerFunction {

    private static final Logger logger = LoggerFactory.getLogger(BlobTriggerFunction.class);
    private static final String STORAGE_CONTAINER = getContainerName();
    private final BlobMetadataService blobMetadataService;
    private final QueueStorageService queueStorageService;


    public BlobTriggerFunction() {
        this.blobMetadataService = new BlobMetadataService();
        this.queueStorageService =
                new QueueStorageService();
    }

    @FunctionName("DocumentMetadataCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "documents/{name}",
                    connection = "AzureWebJobsStorage"
            )
            @BindingName("name")
            String documentName,
            final ExecutionContext context) {

        logger.info("Blob trigger function processed a request for {}: {}", STORAGE_CONTAINER, documentName);
        logger.info("Function execution ID: {}", context.getInvocationId());


        Map<String, String> blobMetadata = blobMetadataService.processBlobMetadata(documentName);

        QueueIngestionMetadata queueMessage = queueStorageService.createQueueMessage(documentName, blobMetadata);

        QueueTaskResult queueTaskResult = queueStorageService.sendToQueue(queueMessage);

        if (queueTaskResult.success()) {
            logger.info("Document {} successfully processed and queued", documentName);
        } else {
            logger.error("Failed to queue {} {}: {}", STORAGE_CONTAINER, documentName, queueTaskResult.errorMessage());
        }

    }
}