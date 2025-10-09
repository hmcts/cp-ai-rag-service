package uk.gov.moj.cp.ingestion.service;

import uk.gov.moj.cp.ingestion.exception.DocumentUploadException;
import uk.gov.moj.cp.ingestion.model.PageChunk;

import java.util.List;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.SearchDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStorageService.class);
    private final SearchClient searchClient;
    private final String indexName;


    public DocumentStorageService(String endpoint, String indexName, String adminKey) {
        this.indexName = indexName;

        if (adminKey != null && !adminKey.isEmpty()) {
            this.searchClient = new SearchClientBuilder()
                    .endpoint(endpoint)
                    .indexName(indexName)
                    .credential(new AzureKeyCredential(adminKey))
                    .buildClient();
        } else {
            // Use Managed Identity authentication
            this.searchClient = new SearchClientBuilder()
                    .endpoint(endpoint)
                    .indexName(indexName)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        }
    }

    public void uploadChunks(List<PageChunk> chunks) {
        LOGGER.info("Uploading to Azure Search Index: {}", indexName);

        for (PageChunk pageChunk : chunks) {
            try {
                if (pageChunk.getContentVector() == null || pageChunk.getContentVector().size() != 3072) {
                    throw new IllegalArgumentException("Invalid embedding for pageChunk on page " + pageChunk.getPageNumber() +
                                                       ". Expected 3072 dimensions, got " + (pageChunk.getContentVector() != null ? pageChunk.getContentVector().size() : "null"));
                }

                SearchDocument searchDocument = new SearchDocument();
                searchDocument.put("id", pageChunk.getId());
                searchDocument.put("chunk", pageChunk.getChunk());
                searchDocument.put("contentVector", pageChunk.getContentVector());
                searchDocument.put("fileName", pageChunk.getFileName());
                searchDocument.put("pageNumber", pageChunk.getPageNumber());
                searchDocument.put("chunkIndex", pageChunk.getChunkIndex());
                searchDocument.put("originalFileUrl", pageChunk.getOriginalFileUrl());
                searchDocument.put("customMetadata", pageChunk.getCustomMetadata());
                // todo
                searchDocument.put("businessDomain", pageChunk.getBusinessDomain());


                searchClient.uploadDocuments(List.of(searchDocument));
            } catch (Exception e) {
                LOGGER.error("Failed to upload pageChunk for page: {}", pageChunk.getPageNumber());
                throw new DocumentUploadException("Failed to upload pageChunk for page " + pageChunk.getPageNumber(), e);
            }
        }
    }
}