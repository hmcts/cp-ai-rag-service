package uk.gov.moj.cp.retrieval.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.index.IndexConstants;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.exception.SearchServiceException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.util.SearchPagedIterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AzureAISearchServiceTest {

    private SearchClient mockSearchClient;
    private DeduplicationService mockDeduplicationService;
    private AzureAISearchService service;
    private MockedStatic<AISearchClientFactory> clientFactoryMockedStatic;

    private final String endpoint = "https://fake-search-service.search.windows.net";
    private final String indexName = "fake-index";

    @BeforeEach
    void setUp() {
        mockSearchClient = mock(SearchClient.class);
        mockDeduplicationService = mock(DeduplicationService.class);
        clientFactoryMockedStatic = mockStatic(AISearchClientFactory.class);
        clientFactoryMockedStatic.when(() -> AISearchClientFactory.getInstance(anyString(), anyString())).thenReturn(mockSearchClient);
        service = new AzureAISearchService(endpoint, indexName, true);
    }

    @AfterEach
    void tearDown() {
        if (null != clientFactoryMockedStatic) {
            clientFactoryMockedStatic.close();
        }
    }

    @Test
    @DisplayName("Throws exception when userQuery is null or empty")
    void throwsExceptionWhenUserQueryIsNullOrEmpty() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, vector, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search("", vector, filters));
    }

    @Test
    @DisplayName("Throws exception when vectorizedUserQuery is null or empty")
    void throwsExceptionWhenVectorizedUserQueryIsNullOrEmpty() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        assertThrows(IllegalArgumentException.class, () -> service.search("query", null, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search("query", Collections.emptyList(), filters));
    }

    @Test
    @DisplayName("Throws exception when metadataFilters is null or empty")
    void throwsExceptionWhenMetadataFiltersIsNullOrEmpty() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        assertThrows(IllegalArgumentException.class, () -> service.search("query", vector, null));
        assertThrows(IllegalArgumentException.class, () -> service.search("query", vector, Collections.emptyList()));
    }

    @Test
    @DisplayName("Returns deduplicated results from search")
    void returnsDeduplicatedResultsFromSearch() throws SearchServiceException {
        final String userQuery = "query";
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        final SearchResult mockResult = mock(SearchResult.class);
        when(mockPagedIterable.iterator()).thenReturn(Arrays.asList(mockResult).iterator());
        final ChunkedEntry entry = ChunkedEntry.builder().id("id").build();
        when(mockResult.getDocument(ChunkedEntry.class)).thenReturn(entry);
        when(mockSearchClient.search(anyString(), any(SearchOptions.class), any())).thenReturn(mockPagedIterable);
        final List<ChunkedEntry> result = service.search(userQuery, vector, filters);
        when(mockDeduplicationService.performSemanticDeduplication(anyList())).thenReturn(List.of(entry));
        assertEquals(1, result.size());
        assertEquals("id", result.get(0).id());
    }

    @Test
    @DisplayName("Throws SearchServiceException on search client failure")
    void throwsSearchServiceExceptionOnSearchClientFailure() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        when(mockSearchClient.search(anyString(), any(SearchOptions.class), any())).thenThrow(new RuntimeException("fail"));
        assertThrows(SearchServiceException.class, () -> service.search("query", vector, filters));
    }

    @Test
    @DisplayName("generateFilterExpression returns isActive != false for empty filters")
    void generateFilterExpressionReturnsIsActiveNeFalseForEmptyFilters() {
        assertThat(service.generateFilterExpression(Collections.emptyList()), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
        assertThat(service.generateFilterExpression(null), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
    }

    @Test
    @DisplayName("generateFilterExpression returns correct filter string for single filter")
    void generateFilterExpressionReturnsCorrectStringForSingleFilter() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("foo", "bar"));
        final String result = service.generateFilterExpression(filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
    }

    @Test
    @DisplayName("generateFilterExpression joins multiple filters with and")
    void generateFilterExpressionJoinsMultipleFiltersWithAnd() throws Exception {

        final List<KeyValuePair> filters = Arrays.asList(
                new KeyValuePair("foo", "bar"),
                new KeyValuePair("baz", "qux")
        );
        final String result = service.generateFilterExpression(filters);
        assertTrue(result.contains(" and "));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'baz' and m/value eq 'qux')"));
    }

    // Unit test for getColumnsToRetrieve
    @Test
    @DisplayName("getColumnsToRetrieve returns correct columns based on deduplication flag")
    void getColumnsToRetrieveReturnsCorrectColumns() {
        // Deduplication enabled
        final AzureAISearchService serviceWithDedup = new AzureAISearchService("endpoint", "index", true);
        final String[] columnsWithDedup = serviceWithDedup.getColumnsToRetrieve();
        assertTrue(List.of(columnsWithDedup).contains(IndexConstants.CHUNK_VECTOR));

        // Deduplication disabled
        final AzureAISearchService serviceWithoutDedup = new AzureAISearchService("endpoint", "index", false);
        final String[] columnsWithoutDedup = serviceWithoutDedup.getColumnsToRetrieve();
        assertFalse(List.of(columnsWithoutDedup).contains(IndexConstants.CHUNK_VECTOR));
    }

}

