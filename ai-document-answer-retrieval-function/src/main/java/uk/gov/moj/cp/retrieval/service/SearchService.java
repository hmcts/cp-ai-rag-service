package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.SearchServiceException;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchService {

    private final AzureAISearchService azureAISearchService;
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);

    // --- Constructor: Initialize Azure AI Search Client ---
    public SearchService() {
        final String endpoint = System.getenv(AZURE_SEARCH_SERVICE_ENDPOINT);
        final String searchIndexName = System.getenv(AZURE_SEARCH_SERVICE_INDEX_NAME);

        azureAISearchService = new AzureAISearchService(endpoint, searchIndexName);
    }

    SearchService(AzureAISearchService azureAISearchService) {
        this.azureAISearchService = azureAISearchService;
    }

    public List<ChunkedEntry> searchDocumentsMatchingFilterCriteria(
            String userQuery,
            List<Double> vectorizedUserQuery,
            List<KeyValuePair> metadataFilters) {

        if (isNullOrEmpty(userQuery) || null == vectorizedUserQuery || vectorizedUserQuery.isEmpty() || null == metadataFilters || metadataFilters.isEmpty()) {
            LOGGER.error("Search Query or Metadata Filters are null or empty");
            throw new IllegalArgumentException("Search Query or Metadata Filters are null or empty");
        }

        try {
            return azureAISearchService.search(userQuery, vectorizedUserQuery, metadataFilters);
        } catch (SearchServiceException e) {
            LOGGER.error("Error occurred while searching documents: {}", e.getMessage());
            return List.of();
        }
    }
}