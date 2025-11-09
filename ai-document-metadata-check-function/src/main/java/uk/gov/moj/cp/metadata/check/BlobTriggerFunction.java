package uk.gov.moj.cp.metadata.check;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;

import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;

import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobTriggerFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobTriggerFunction.class);
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
                    connection = AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT
            )
            @BindingName("name") String documentName,
            @QueueOutput(name = "queueMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION + "%",
                    connection = AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT)
            OutputBinding<String> queueMessage) {

        LOGGER.info("Blob trigger function processed a request for document: {}", documentName);
        orchestratorService.processDocument(documentName, queueMessage);
    }
}


