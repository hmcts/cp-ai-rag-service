package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.index.IndexConstants;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.retrieval.SearchServiceException;
import uk.gov.moj.cp.retrieval.model.KeyValuePair;

import java.util.ArrayList;
import java.util.List;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.Context;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.VectorSearchOptions;
import com.azure.search.documents.models.VectorizedQuery;
import com.azure.search.documents.util.SearchPagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AzureAISearchService {

    private static final int K_NEAREST_NEIGHBORS_COUNT = 50;
    private final SearchClient searchClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(AzureAISearchService.class);

    public AzureAISearchService(String endpoint, String apiKey, String searchIndexName) {

        if (isNullOrEmpty(endpoint) || isNullOrEmpty(searchIndexName)) {
            throw new IllegalArgumentException("Azure AI Search endpoint and index name must be set as environment variables.");
        }

        this.searchClient = new SearchClientBuilder()
                .endpoint(endpoint)
                .indexName(searchIndexName)
                .credential(new AzureKeyCredential(apiKey))
                .buildClient();
        LOGGER.info("Initialized Azure AI Search client with API Key.");

    }

    public AzureAISearchService(String endpoint, String searchIndexName) {

        if (isNullOrEmpty(endpoint) || isNullOrEmpty(searchIndexName)) {
            throw new IllegalArgumentException("Azure AI Search endpoint and index name must be set as environment variables.");
        }

        this.searchClient = new SearchClientBuilder()
                .endpoint(endpoint)
                .indexName(searchIndexName)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        LOGGER.info("Initialized Azure AI Search client with managed identity.");

    }

    public List<ChunkedEntry> search(
            String userQuery,
            List<Double> vectorizedUserQuery,
            List<KeyValuePair> metadataFilters) throws SearchServiceException {

        LOGGER.info("Retrieving documents for query with filters: {}", metadataFilters);

        final String filterExpression = generateFilterExpression(metadataFilters);
        LOGGER.info("Retrieving documents for query with filters: {}", filterExpression);


        // 2. Define VectorQuery for semantic search
        VectorizedQuery vectorizedQuery = new VectorizedQuery(
                vectorizedUserQuery.stream().map(Double::floatValue).collect(ArrayList::new, ArrayList::add, ArrayList::addAll)
        )
                .setKNearestNeighborsCount(K_NEAREST_NEIGHBORS_COUNT) // Number of nearest neighbors to retrieve
                .setFields(IndexConstants.CHUNK_VECTOR);

        VectorSearchOptions vectorSearchOptions = new VectorSearchOptions().setQueries(List.of(vectorizedQuery));

        // 3. Define SearchOptions
        SearchOptions searchOptions = new SearchOptions()
                .setFilter(filterExpression) // Apply the OData filter
                .setVectorSearchOptions(vectorSearchOptions) // Add the vector query
                .setQueryType(QueryType.FULL) // Use SEMANTIC for hybrid search with semantic ranking
                .setSelect( // Select all fields needed for LLM context and citation
                        IndexConstants.ID,
                        IndexConstants.CHUNK,
                        IndexConstants.DOCUMENT_FILE_NAME,
                        IndexConstants.DOCUMENT_ID,
                        IndexConstants.PAGE_NUMBER,
                        IndexConstants.DOCUMENT_FILE_URL
                )
                .setTop(K_NEAREST_NEIGHBORS_COUNT); // Number of top results to return after filtering and ranking


        // 4. Execute the search
        try {
            SearchPagedIterable searchResults = searchClient.search(userQuery, searchOptions, Context.NONE); // userQuery for keyword search and semantic ranking

            List<ChunkedEntry> chunkedEntries = new ArrayList<>();
            for (SearchResult result : searchResults) {
                // Get the full document map for each chunk
                ChunkedEntry chunkedEntry = result.getDocument(ChunkedEntry.class);
                chunkedEntries.add(chunkedEntry);
            }
            LOGGER.info("Successfully retrieved {}  documents from Azure AI Search.", chunkedEntries.size());
            return chunkedEntries;

        } catch (Exception e) {
            // Implement retry logic here if needed
            throw new SearchServiceException("Failed to retrieve documents from Azure AI Search", e);
        }
    }

    private String generateFilterExpression(final List<KeyValuePair> metadataFilters) {
        StringBuilder filterBuilder = new StringBuilder();

        if (metadataFilters != null && !metadataFilters.isEmpty()) {
            for (KeyValuePair pair : metadataFilters) {
                String key = pair.key();
                String value = pair.value();
                if (!filterBuilder.isEmpty()) {
                    filterBuilder.append(" and ");
                }
                // Use 'any' operator for Collection(Edm.ComplexType)
                // This filters for any item in the 'customMetadata' collection where 'key' equals 'key' AND 'value' equals 'value'
                filterBuilder.append(String.format("%s/any(m: m/key eq '%s' and m/value eq '%s')", IndexConstants.CUSTOM_METADATA, key, value));
            }
        }
        return !filterBuilder.isEmpty() ? filterBuilder.toString() : null;
    }
}