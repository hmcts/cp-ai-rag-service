package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_INDEX;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_VECTOR;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_URL;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.PAGE_NUMBER;
import static uk.gov.moj.cp.ai.index.IndexConstants.VECTOR_DIMENSIONS;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ingestion.exception.DocumentUploadException;

import java.util.ArrayList;
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

    public void uploadChunks(List<ChunkedEntry> chunks) {
        LOGGER.info("Uploading {} chunks to Azure Search Index: {}", chunks.size(), indexName);

        try {
            List<SearchDocument> batch = new ArrayList<>(chunks.size());

            for (ChunkedEntry chunkedEntry : chunks) {
                if (chunkedEntry.chunkVector() == null || chunkedEntry.chunkVector().size() != VECTOR_DIMENSIONS) {
                    LOGGER.warn("Skipping invalid embedding for page {} (vector size: {})",
                            chunkedEntry.pageNumber(),
                            chunkedEntry.chunkVector() != null ? chunkedEntry.chunkVector().size() : null);
                    continue;
                }

                SearchDocument searchDocument = new SearchDocument();
                // Use exact field names from vector database schema
                searchDocument.put(ID, chunkedEntry.id());
                searchDocument.put(CHUNK, chunkedEntry.chunk());
                searchDocument.put(CHUNK_VECTOR, chunkedEntry.chunkVector());
                searchDocument.put(DOCUMENT_FILE_NAME, chunkedEntry.documentFileName());
                searchDocument.put(DOCUMENT_ID, chunkedEntry.documentId());
                searchDocument.put(PAGE_NUMBER, chunkedEntry.pageNumber());
                searchDocument.put(CHUNK_INDEX, chunkedEntry.chunkIndex());
                searchDocument.put(DOCUMENT_FILE_URL, chunkedEntry.documentFileUrl());
                searchDocument.put(CUSTOM_METADATA, chunkedEntry.customMetadata());

                batch.add(searchDocument);
            }

            if (!batch.isEmpty()) {
                searchClient.uploadDocuments(batch);
                LOGGER.info("Batch upload successful for index {}", indexName);
            } else {
                LOGGER.warn("No valid chunks found to upload for index {}", indexName);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to upload chunk batch to Azure Search index {}", indexName, e);
            throw new DocumentUploadException("Batch upload failed for index " + indexName, e);
        }
    }
}