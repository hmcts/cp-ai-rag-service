package uk.gov.moj.cp.metadata.check;

import static uk.gov.moj.cp.metadata.check.util.BlobUtil.createQueueMessage;
import static uk.gov.moj.cp.metadata.check.util.BlobUtil.isValidMetadata;

import uk.gov.moj.cp.metadata.check.config.Config;
import uk.gov.moj.cp.metadata.check.service.BlobMetadataValidationService;
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

    private final BlobMetadataValidationService blobMetadataValidationService;
    private final QueueStorageService queueStorageService;

    public BlobTriggerFunction(final BlobMetadataValidationService blobMetadataValidationService,
                               final QueueStorageService queueStorageService) {
        this.blobMetadataValidationService = blobMetadataValidationService;
        this.queueStorageService = queueStorageService;
    }

    @FunctionName("DocumentMetadataCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "documents/{name}",
                    connection = "AzureWebJobsStorage"
            ) String documentName,
            @BindingName("name") String blobNameParam,
            final ExecutionContext context) {

        logger.info("Blob trigger function processed a request for document: {}", documentName);
        logger.info("Function execution ID: {}", context.getInvocationId());

        try {

            Map<String, String> blobMetadata = blobMetadataValidationService.extractBlobMetadata(documentName);

            if (!isValidMetadata(blobMetadata)) {
                logger.error("Invalid metadata for blob: {}", documentName);
                return;
            }

            Map<String, Object> queueMessage = createQueueMessage(documentName, blobMetadata,
                    Config.getStorageAccountName(), Config.getContainerName());


            queueStorageService.sendToQueue(queueMessage);

            logger.info("Document {} successfully processed and queued", documentName);

        } catch (Exception e) {
            logger.error("Error processing blob: {}", documentName, e);
            throw e;
        }
    }
}