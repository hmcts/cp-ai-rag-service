package uk.gov.moj.cp.ingestion.service;

import static java.lang.String.format;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.joining;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_INDEX;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_VECTOR;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_URL;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.FALSE_VALUE;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.IS_ACTIVE;
import static uk.gov.moj.cp.ai.index.IndexConstants.PAGE_NUMBER;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.util.SearchPagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentStorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentStorageService.class);
    private final SearchClient searchClient;
    private final String indexName;

    public static final int VECTOR_DIMENSIONS = 3072;

    public DocumentStorageService(String endpoint, String indexName) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(indexName)) {
            throw new IllegalArgumentException("Document Storage Endpoint and Vector Index Name cannot be null or empty");
        }

        LOGGER.info("Connecting to Azure AI Search endpoint '{}' and index '{}'", endpoint, indexName);

        this.indexName = indexName;

        this.searchClient = AISearchClientFactory.getInstance(endpoint, indexName);

        LOGGER.info("Initialized Azure AI Search client with managed identity.");
    }

    public DocumentStorageService(final SearchClient searchClient) {
        if (isNull(searchClient)) {
            throw new IllegalArgumentException("Document Storage searchClient cannot be null");
        }
        this.indexName = searchClient.getIndexName();
        this.searchClient = searchClient;
    }

    public void uploadChunks(List<ChunkedEntry> chunks) throws DocumentProcessingException {
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
            final String errorMessage = "Failed to upload list of chunks to Azure Search index " + indexName;
            LOGGER.error(errorMessage, e);
            throw new DocumentProcessingException(errorMessage, e);
        }
    }

    @SuppressWarnings("unchecked")
    public void markDocumentsInActive(final List<String> supersededDocuments) {
        final List<SearchDocument> allUpdates = new ArrayList<>();

        final SearchPagedIterable searchResults = getSearchResults(supersededDocuments);

        for (SearchResult result : searchResults) {
            final SearchDocument searchDocument = result.getDocument(SearchDocument.class);

            final List<Map<String, String>> customMetadata = searchDocument.containsKey(CUSTOM_METADATA)
                    ? (List<Map<String, String>>) searchDocument.get(CUSTOM_METADATA)
                    : new ArrayList<>();
            Map<String, String> isActiveKeyValue = new HashMap<>();
            isActiveKeyValue.put("key", IS_ACTIVE);
            isActiveKeyValue.put("value", FALSE_VALUE);
            customMetadata.add(isActiveKeyValue);

            allUpdates.add(getSearchDocument(searchDocument, customMetadata));
        }

        if (!allUpdates.isEmpty()) {
            searchClient.mergeDocuments(allUpdates);
        }
    }

    private SearchPagedIterable getSearchResults(final List<String> supersededDocuments) {
        final String filter = supersededDocuments.stream()
                .map(id -> format("%s/any(m: m/key eq '%s' and m/value eq '%s')", CUSTOM_METADATA, DOCUMENT_ID, id))
                .collect(joining(" or "));

        LOGGER.info("Find search results matching filter criteria: {}", filter);

        final SearchOptions options = new SearchOptions()
                .setFilter(filter)
                .setSelect(format("%s, %s", ID, CUSTOM_METADATA));

        return searchClient.search("*", options, Context.NONE);
    }

    private static SearchDocument getSearchDocument(final SearchDocument doc, final List<Map<String, String>> metadata) {
        final SearchDocument updateDoc = new SearchDocument();
        updateDoc.put(ID, doc.get(ID));
        updateDoc.put(CUSTOM_METADATA, metadata);
        return updateDoc;
    }

}