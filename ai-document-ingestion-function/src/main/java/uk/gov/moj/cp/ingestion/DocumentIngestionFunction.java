package uk.gov.moj.cp.ingestion;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for document ingestion processing.
 */
public class DocumentIngestionFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionFunction.class);
    private final DocumentIngestionOrchestrator documentIngestionOrchestrator;

    public DocumentIngestionFunction() {
        this.documentIngestionOrchestrator = new DocumentIngestionOrchestrator();
    }

    DocumentIngestionFunction(DocumentIngestionOrchestrator documentIngestionOrchestrator) {
        this.documentIngestionOrchestrator = documentIngestionOrchestrator;
    }

    @FunctionName("DocumentIngestion")
    public void run(
            @QueueTrigger(
                    name = "queueMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) String queueMessage,
            @BindingName("DequeueCount") long dequeueCount
    ) throws DocumentProcessingException {

        LOGGER.info("Document ingestion function triggered ");
        final int maxDequeueCount = getRequiredEnvAsInteger("AzureFunctionsJobHost__extensions__queues__maxDequeueCount", "3");

        try {
            if (isNullOrEmpty(queueMessage)) {
                LOGGER.error("Invalid queue queueMessage received: {}", queueMessage);
                return;
            }

            final QueueIngestionMetadata queueIngestionMetadata =
                    getObjectMapper().readValue(queueMessage, QueueIngestionMetadata.class);


            LOGGER.info("Parsed ingestion metadata - ID: {}, Name: {}, Blob URL: {}",
                    queueIngestionMetadata.documentId(),
                    queueIngestionMetadata.documentName(),
                    queueIngestionMetadata.blobUrl());

            documentIngestionOrchestrator.processQueueMessage(queueIngestionMetadata);

        } catch (DocumentProcessingException documentProcessingException) {
            if (dequeueCount == maxDequeueCount) {
                documentIngestionOrchestrator.processQueueMessageFailed(queueMessage);
            } else {
                // Re-throw to trigger Azure Function retry mechanism
                throw new DocumentProcessingException("Error processing queueMessage", documentProcessingException);
            }
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize queue message: {}", queueMessage, e);
        }
    }
}
