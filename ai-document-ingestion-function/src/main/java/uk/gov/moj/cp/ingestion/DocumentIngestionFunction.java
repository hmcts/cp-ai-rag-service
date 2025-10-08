package uk.gov.moj.cp.ingestion;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for document ingestion processing.
 */
public class DocumentIngestionFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionFunction.class);
    private static final String STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION = "%STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION%";
    private static final String AI_RAG_SERVICE_STORAGE_ACCOUNT = "AI_RAG_SERVICE_STORAGE_ACCOUNT";
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
                    queueName = STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION,
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT
            ) String queueMessage) throws DocumentProcessingException, Exception {

        LOGGER.info("Document ingestion function triggered ");

        try {
            if (isNullOrEmpty(queueMessage)) {
                LOGGER.error("Invalid queue queueMessage received: {}", queueMessage);
                return;
            }

            documentIngestionOrchestrator.processQueueMessage(queueMessage);

        } catch (DocumentProcessingException documentProcessingException) {
            // Re-throw to trigger Azure Function retry mechanism
            throw documentProcessingException;
        }
    }
}
