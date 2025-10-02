package uk.gov.moj.cp.metadata.check;

import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.BlobTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobTriggerFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobTriggerFunction.class);
    private final DocumentMetadataService documentMetadataService;
    private final QueueStorageService queueStorageService;
    private final TableStorageService tableStorageService;
    private final IngestionOrchestratorService orchestratorService;


    public BlobTriggerFunction() {
        String storageConnectionString = System.getenv("STORAGE_ACCOUNT_CONNECTION_STRING");
        String documentIngestionQueue = System.getenv("STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION_QUEUE");
        String documentIngestionOutcomeTable = System.getenv("STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME");
        String documentContainerName = System.getenv("STORAGE_ACCOUNT_BLOB_CONTAINER_NAME");

        this.documentMetadataService = new DocumentMetadataService(storageConnectionString,
                documentContainerName,
                documentIngestionOutcomeTable);

        this.queueStorageService = new QueueStorageService(storageConnectionString,
                documentIngestionQueue);

        this.tableStorageService =
                new TableStorageService(storageConnectionString, documentIngestionOutcomeTable);

        this.orchestratorService = new IngestionOrchestratorService(
                documentMetadataService, queueStorageService, tableStorageService
        );
    }

    BlobTriggerFunction(DocumentMetadataService documentMetadataService,
                        QueueStorageService queueStorageService,
                        TableStorageService tableStorageService,
                        IngestionOrchestratorService orchestratorService

    ) {
        this.documentMetadataService = documentMetadataService;
        this.queueStorageService = queueStorageService;
        this.tableStorageService = tableStorageService;
        this.orchestratorService = orchestratorService;
    }

    @FunctionName("DocumentMetadataCheck")
    public void run(
            @BlobTrigger(
                    name = "blob",
                    path = "documents/{name}",
                    connection = "AI_RAG_SERVICE_STORAGE_ACCOUNT"
            )
            @BindingName("name") String documentName) {

        LOGGER.info("Blob trigger function processed a request for document: {}", documentName);

        orchestratorService.processDocument(documentName);
    }
}
