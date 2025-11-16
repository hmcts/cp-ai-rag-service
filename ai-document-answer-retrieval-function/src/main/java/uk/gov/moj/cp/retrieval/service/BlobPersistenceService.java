package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS;

import uk.gov.moj.cp.ai.service.BlobClientService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobPersistenceService.class);

    private final BlobClientService blobClientService;

    public BlobPersistenceService() {
        final String endpoint = System.getenv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT);
        final String documentContainerName = System.getenv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS);

        this.blobClientService = BlobClientService.getInstance(endpoint, documentContainerName);
    }

    public BlobPersistenceService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public void saveBlob(final String filename, final String payload) {
        blobClientService.addBlob(filename, payload);
        LOGGER.info("Blob '{}' saved successfully.", filename);
    }
}
