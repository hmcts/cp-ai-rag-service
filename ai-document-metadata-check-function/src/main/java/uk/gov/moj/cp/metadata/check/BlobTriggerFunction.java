package uk.gov.moj.cp.metadata.check;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;

import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.TableOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobTriggerFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobTriggerFunction.class);
    private static final String STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION = "%STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION%";
    private static final String STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME = "%STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME%";
    private static final String AI_RAG_SERVICE_STORAGE_ACCOUNT = "AI_RAG_SERVICE_STORAGE_ACCOUNT";
    private final DocumentMetadataService documentMetadataService;
    private final IngestionOrchestratorService orchestratorService;


    public BlobTriggerFunction() {
        this.documentMetadataService = new DocumentMetadataService();
        this.orchestratorService = new IngestionOrchestratorService(documentMetadataService);
    }

    BlobTriggerFunction(DocumentMetadataService documentMetadataService,
                        IngestionOrchestratorService orchestratorService) {
        this.documentMetadataService = documentMetadataService;
        this.orchestratorService = orchestratorService;
    }

    @FunctionName("DocumentMetadataCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "documents/{name}",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT
            )
            @BindingName("name") String documentName,
            @QueueOutput(name = "queueMessage",
                    queueName = STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION,
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT)
            OutputBinding<String> queueMessage,
            @TableOutput(name = "messageOutcome",
                    tableName = STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME,
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT)
            OutputBinding<DocumentIngestionOutcome> messageOutcome) {

        LOGGER.info("Blob trigger function processed a request for document: {}", documentName);
        orchestratorService.processDocument(documentName, queueMessage, messageOutcome);
    }
}


