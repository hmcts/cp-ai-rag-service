package uk.gov.moj.cp.ingestion.service;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.FALSE_VALUE;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.IS_ACTIVE;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.util.SearchPagedIterable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentStorageServiceTest {

    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        documentStorageService = new DocumentStorageService("https://test-search.endpoint", "test-index");
    }

    @Test
    @DisplayName("Handle Null Chunks List")
    void shouldHandleNullChunksList() throws Exception {
        // when & then
        assertThrows(NullPointerException.class,
                () -> documentStorageService.uploadChunks(null));
    }

    @Test
    @DisplayName("Handle Empty Chunks List")
    void shouldHandleEmptyChunksList() throws Exception {
        // given
        List<ChunkedEntry> emptyChunks = Collections.emptyList();

        // when & then
        assertDoesNotThrow(() -> documentStorageService.uploadChunks(emptyChunks));
    }

    @Test
    @DisplayName("Service Constructor Works")
    void shouldCreateServiceWithValidParameters() {
        // when
        DocumentStorageService service = new DocumentStorageService("https://test-endpoint", "test-index");

        // then
        assertNotNull(service);
    }

    @Test
    @DisplayName("Service Constructor with Null Or Empty Endpoint should Throw Exception")
    void shouldThrowExceptionWithNullAdminKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new DocumentStorageService("", "test-index"));

        assertThrows(IllegalArgumentException.class, () ->
                new DocumentStorageService(null, "test-index"));
    }

    @Test
    @DisplayName("Service Constructor with Null Or Empty Index Name Should Throw Exception")
    void shouldThrowExceptionWithEmptyAdminKey() {
        assertThrows(IllegalArgumentException.class, () ->
            new DocumentStorageService("https://test-endpoint", ""));
        assertThrows(IllegalArgumentException.class, () ->
                new DocumentStorageService("https://test-endpoint", null));
    }

    @Test
    void shouldMarkDocumentsInactive_andCallMerge() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        List<String> ids = List.of("doc1", "doc2");
        final SearchDocument doc1 = new SearchDocument();
        doc1.put(ID, "doc1");
        doc1.put(CUSTOM_METADATA, new HashMap<>(Map.of("is_active", "true")));

        final SearchDocument doc2 = new SearchDocument();
        doc2.put(ID, "doc2");
        doc2.put(CUSTOM_METADATA, new HashMap<>());

        final SearchResult result1 = mock(SearchResult.class);
        final SearchResult result2 = mock(SearchResult.class);

        when(result1.getDocument(SearchDocument.class)).thenReturn(doc1);
        when(result2.getDocument(SearchDocument.class)).thenReturn(doc2);

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(List.of(result1, result2).iterator());

        when(searchClient.search(anyString(), any(SearchOptions.class), any())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(ids);

        // then
        final ArgumentCaptor<List<SearchDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchClient).mergeDocuments(captor.capture());

        final List<SearchDocument> updatedDocs = captor.getValue();

        assertThat(updatedDocs.size(), is(2));

        for (SearchDocument doc : updatedDocs) {
            Map<String, String> metadata = (Map<String, String>) doc.get(CUSTOM_METADATA);
            assertThat(metadata.get(IS_ACTIVE), is(FALSE_VALUE));
        }
    }

    @Test
    void shouldNotCallMerge_whenNoResultsFound() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        when(searchClient.search(anyString(), any(SearchOptions.class), any()))
                .thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(ids);

        // then
        verify(searchClient, never()).mergeDocuments(anyList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldCreateMetadata_whenMissing() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1");
        final SearchDocument doc = new SearchDocument();
        doc.put(ID, "doc1");
        // no metadata

        final SearchResult result = mock(SearchResult.class);
        when(result.getDocument(SearchDocument.class)).thenReturn(doc);

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(List.of(result).iterator());
        when(searchClient.search(anyString(), any(SearchOptions.class), any())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(ids);

        // then
        final ArgumentCaptor<List<SearchDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchClient).mergeDocuments(captor.capture());

        final Map<String, String> metadata = (Map<String, String>) captor.getValue().get(0).get(CUSTOM_METADATA);
        assertThat(metadata.get(IS_ACTIVE), is(FALSE_VALUE));
    }

    @Test
    void shouldBuildCorrectFilter() {
        // given
        final SearchClient searchClient = mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        final DocumentStorageService documentStorageService = new DocumentStorageService(searchClient);

        final List<String> ids = List.of("doc1", "doc2");

        final SearchPagedIterable iterable = mock(SearchPagedIterable.class);
        when(iterable.iterator()).thenReturn(Collections.emptyIterator());

        final ArgumentCaptor<SearchOptions> optionsCaptor = ArgumentCaptor.forClass(SearchOptions.class);
        when(searchClient.search(anyString(), optionsCaptor.capture(), any())).thenReturn(iterable);

        // when
        documentStorageService.markDocumentsInActive(ids);

        // then
        final String filter = optionsCaptor.getValue().getFilter();

        assertThat(filter.contains("documentId eq 'doc1'"), is(true));
        assertThat(filter.contains("documentId eq 'doc2'"), is(true));
        assertThat(filter.contains("or"), is(true));
    }
}