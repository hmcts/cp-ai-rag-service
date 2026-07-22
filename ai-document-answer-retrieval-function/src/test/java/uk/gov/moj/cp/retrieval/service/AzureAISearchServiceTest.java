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
import uk.gov.moj.cp.retrieval.service.filter.DeduplicationService;

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
import org.mockito.ArgumentCaptor;
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
        service = new AzureAISearchService(endpoint, indexName);
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
        assertThrows(IllegalArgumentException.class, () -> service.search(null, null, vector, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "", vector, filters));
    }

    @Test
    @DisplayName("Throws exception when vectorizedUserQuery is null or empty")
    void throwsExceptionWhenVectorizedUserQueryIsNullOrEmpty() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", null, filters));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", Collections.emptyList(), filters));
    }

    @Test
    @DisplayName("Throws exception when metadataFilters is null or empty")
    void throwsExceptionWhenMetadataFiltersIsNullOrEmpty() {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", vector, null));
        assertThrows(IllegalArgumentException.class, () -> service.search(null, "query", vector, Collections.emptyList()));
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
        final List<ChunkedEntry> result = service.search(null, userQuery, vector, filters);
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
        assertThrows(SearchServiceException.class, () -> service.search(null, "query", vector, filters));
    }

    @Test
    @DisplayName("generateFilterExpression returns isActive != false for empty filters")
    void generateFilterExpressionReturnsIsActiveNeFalseForEmptyFilters() {
        assertThat(service.generateFilterExpression(null, Collections.emptyList()), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
        assertThat(service.generateFilterExpression(null, null), is("(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))"));
    }

    @Test
    @DisplayName("generateFilterExpression returns correct filter string for single filter")
    void generateFilterExpressionReturnsCorrectStringForSingleFilter() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("foo", "bar"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
    }

    @Test
    @DisplayName("generateFilterExpression joins multiple filters with and")
    void generateFilterExpressionJoinsMultipleFiltersWithAnd() throws Exception {

        final List<KeyValuePair> filters = Arrays.asList(
                new KeyValuePair("foo", "bar"),
                new KeyValuePair("baz", "qux")
        );
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains(" and "));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'foo' and m/value eq 'bar')"));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'baz' and m/value eq 'qux')"));
    }

    private static final String IS_ACTIVE_FILTER_LITERAL =
            "(not customMetadata/any(m: m/key eq 'is_active') or customMetadata/any(m: m/key eq 'is_active' and m/value ne 'false'))";

    @Test
    @DisplayName("generateFilterExpression escapes single quote in value")
    void generateFilterExpression_escapesSingleQuoteInValue() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "Crown v O'Brien"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'caseName' and m/value eq 'Crown v O''Brien')"));
    }

    @Test
    @DisplayName("generateFilterExpression escapes single quote in key")
    void generateFilterExpression_escapesSingleQuoteInKey() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("o'key", "v"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'o''key' and m/value eq 'v')"));
    }

    @Test
    @DisplayName("generateFilterExpression neutralises OData injection in value")
    void generateFilterExpression_neutralisesODataInjectionPayloadInValue() {
        // Pre-fix: this payload would close the literal early and inject `or 'a' eq 'a'`,
        // making the any() predicate vacuously true for every chunk -> filter bypass.
        // Post-fix: doubled quotes demote the entire payload to an inert string literal.
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "x' or 'a' eq 'a"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'k' and m/value eq 'x'' or ''a'' eq ''a')"));
    }

    @Test
    @DisplayName("generateFilterExpression neutralises is_active bypass payload")
    void generateFilterExpression_neutralisesIsActiveBypassPayload() {
        // Pre-fix: an injected top-level `or` could lift the user clause outside the and-joined
        // IS_ACTIVE_FILTER guard (since `and` binds tighter than `or`), surfacing soft-deleted
        // documents. Post-fix: the entire payload is contained inside one string literal.
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "x') or (true"));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("m/value eq 'x'') or (true'"));
        // The security-trimming guard must remain the outermost and-joined trailing clause.
        assertTrue(result.endsWith(" and " + IS_ACTIVE_FILTER_LITERAL),
                "IS_ACTIVE_FILTER must remain the trailing and-joined clause; was: " + result);
    }

    @Test
    @DisplayName("generateFilterExpression handles empty string value")
    void generateFilterExpression_handlesEmptyStringValue() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", ""));
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'k' and m/value eq '')"));
    }

    @Test
    @DisplayName("generateFilterExpression preserves is_active trailer with escaped pair")
    void generateFilterExpression_preservesIsActiveTrailerWithEscapedPair() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        final String result = service.generateFilterExpression(null, filters);
        assertThat(result, is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression applies escape to every pair when mixed")
    void generateFilterExpression_appliesEscapeToEveryPairWhenMixed() {
        final List<KeyValuePair> filters = Arrays.asList(
                new KeyValuePair("plain", "value"),
                new KeyValuePair("caseName", "O'Brien")
        );
        final String result = service.generateFilterExpression(null, filters);
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'plain' and m/value eq 'value')"));
        assertTrue(result.contains("customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"));
        assertTrue(result.contains(" and "));
        assertTrue(result.endsWith(" and " + IS_ACTIVE_FILTER_LITERAL));
    }

    // Unit test for getColumnsToRetrieve
    @Test
    @DisplayName("getColumnsToRetrieve always includes the chunk vector column")
    void getColumnsToRetrieveAlwaysIncludesVector() {
        // The search service is agnostic of the dedup/MMR toggles; the vector is always fetched and
        // the downstream services decide whether to use it.
        final List<String> columns = List.of(service.getColumnsToRetrieve(null));
        assertTrue(columns.contains(IndexConstants.CHUNK_VECTOR));
        assertTrue(columns.contains(IndexConstants.CHUNK));
        assertTrue(columns.contains(IndexConstants.ID));
    }

    @Test
    @DisplayName("generateFilterExpression prepends the client-scoping clause when a client id is present")
    void generateFilterExpression_prependsClientScopingClauseWhenClientIdPresent() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        final String result = service.generateFilterExpression("client-a", filters);
        assertThat(result, is(
                "clientId eq 'client-a'"
                        + " and "
                        + "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression prepends the client-scoping clause with no metadata filters")
    void generateFilterExpression_prependsClientScopingClauseWithNoMetadataFilters() {
        final String result = service.generateFilterExpression("client-a", Collections.emptyList());
        assertThat(result, is("clientId eq 'client-a' and " + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression escapes single quote in the client id")
    void generateFilterExpression_escapesSingleQuoteInClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("foo", "bar"));
        final String result = service.generateFilterExpression("a'b", filters);
        assertTrue(result.startsWith("clientId eq 'a''b' and "),
                "client-scoping clause must lead with the escaped client id; was: " + result);
    }

    @Test
    @DisplayName("generateFilterExpression is unchanged for a null client id")
    void generateFilterExpression_isUnchangedForNullClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        assertThat(service.generateFilterExpression(null, filters), is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("generateFilterExpression is unchanged for an empty client id")
    void generateFilterExpression_isUnchangedForEmptyClientId() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("caseName", "O'Brien"));
        assertThat(service.generateFilterExpression("", filters), is(
                "customMetadata/any(m: m/key eq 'caseName' and m/value eq 'O''Brien')"
                        + " and "
                        + IS_ACTIVE_FILTER_LITERAL));
    }

    @Test
    @DisplayName("getColumnsToRetrieve includes the client id column when a client scope is supplied")
    void getColumnsToRetrieveIncludesClientIdColumn() {
        final List<String> columns = List.of(service.getColumnsToRetrieve("client-a"));
        assertTrue(columns.contains(IndexConstants.CLIENT_ID));
    }

    @Test
    @DisplayName("getColumnsToRetrieve omits the client id column when no client scope is supplied — the live index may not define the field")
    void getColumnsToRetrieveOmitsClientIdColumn_whenNoClientScope() {
        final List<String> columns = List.of(service.getColumnsToRetrieve(null));
        assertFalse(columns.contains(IndexConstants.CLIENT_ID));
    }

    @Test
    @DisplayName("search applies the client-scoping clause to the query filter")
    void search_appliesClientScopingClauseToQueryFilter() throws SearchServiceException {
        final List<Float> vector = Arrays.asList(1.0f, 2.0f);
        final List<KeyValuePair> filters = List.of(new KeyValuePair("k", "v"));
        final SearchPagedIterable mockPagedIterable = mock(SearchPagedIterable.class);
        when(mockPagedIterable.iterator()).thenReturn(Collections.<SearchResult>emptyList().iterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(mockSearchClient.search(anyString(), optionsCaptor.capture(), any())).thenReturn(mockPagedIterable);

        service.search("client-a", "query", vector, filters);

        assertTrue(optionsCaptor.getValue().getFilter().startsWith("clientId eq 'client-a' and "),
                "search filter must lead with the client-scoping clause; was: " + optionsCaptor.getValue().getFilter());
    }

}

