package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.retrieval.SearchServiceException;
import uk.gov.moj.cp.retrieval.model.KeyValuePair;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class SearchServiceTest {

    @Mock
    private AzureAISearchService mockAzureAISearchService;

    private SearchService searchService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        searchService = new SearchService(mockAzureAISearchService);
    }

    @Test
    void searchDocumentsMatchingFilterCriteria_ReturnsResults_WhenValidInputsProvided() throws SearchServiceException {
        String userQuery = "Find legal documents";
        List<Double> vectorizedUserQuery = List.of(0.1, 0.2, 0.3);
        List<KeyValuePair> metadataFilters = List.of(new KeyValuePair("key", "value"));
        List<ChunkedEntry> expectedResults = List.of(new ChunkedEntry("id1", "content1", "doc1", 1, "docId1"));

        when(mockAzureAISearchService.search(userQuery, vectorizedUserQuery, metadataFilters)).thenReturn(expectedResults);

        List<ChunkedEntry> results = searchService.searchDocumentsMatchingFilterCriteria(userQuery, vectorizedUserQuery, metadataFilters);

        assertEquals(expectedResults, results);
        verify(mockAzureAISearchService).search(userQuery, vectorizedUserQuery, metadataFilters);
    }

    @Test
    void searchDocumentsMatchingFilterCriteria_ReturnsEmptyList_WhenNoResultsFound() throws SearchServiceException {
        String userQuery = "Find legal documents";
        List<Double> vectorizedUserQuery = List.of(0.1, 0.2, 0.3);
        List<KeyValuePair> metadataFilters = List.of(new KeyValuePair("key", "value"));

        when(mockAzureAISearchService.search(userQuery, vectorizedUserQuery, metadataFilters))
                .thenReturn(List.of());

        List<ChunkedEntry> results = searchService.searchDocumentsMatchingFilterCriteria(userQuery, vectorizedUserQuery, metadataFilters);

        assertEquals(List.of(), results);
        verify(mockAzureAISearchService).search(userQuery, vectorizedUserQuery, metadataFilters);
    }

    @Test
    void searchDocumentsMatchingFilterCriteria_ThrowsException_WhenSearchServiceFails() throws SearchServiceException {
        String userQuery = "Find legal documents";
        List<Double> vectorizedUserQuery = List.of(0.1, 0.2, 0.3);
        List<KeyValuePair> metadataFilters = List.of(new KeyValuePair("key", "value"));

        when(mockAzureAISearchService.search(userQuery, vectorizedUserQuery, metadataFilters))
                .thenThrow(new SearchServiceException("Search service error"));

        final List<ChunkedEntry> chunkedEntries = searchService.searchDocumentsMatchingFilterCriteria(userQuery, vectorizedUserQuery, metadataFilters);
        assertEquals(0, chunkedEntries.size());
        verify(mockAzureAISearchService).search(userQuery, vectorizedUserQuery, metadataFilters);
    }

    @Test
    void searchDocumentsMatchingFilterCriteria_ThrowsException_WhenInputsAreNull() throws SearchServiceException {

        String userQuery = "Find legal documents";
        List<Double> vectorizedUserQuery = List.of(0.1, 0.2, 0.3);
        List<KeyValuePair> metadataFilters = List.of(new KeyValuePair("key", "value"));
        when(mockAzureAISearchService.search(null, vectorizedUserQuery, metadataFilters))
                .thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria("   ", vectorizedUserQuery, metadataFilters));
        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria(null, vectorizedUserQuery, metadataFilters));
        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria(userQuery, List.of(), metadataFilters));
        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria(userQuery, null, metadataFilters));
        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria(userQuery, vectorizedUserQuery, List.of()));
        assertThrows(IllegalArgumentException.class, () -> searchService.searchDocumentsMatchingFilterCriteria(userQuery, vectorizedUserQuery, null));

    }
}
