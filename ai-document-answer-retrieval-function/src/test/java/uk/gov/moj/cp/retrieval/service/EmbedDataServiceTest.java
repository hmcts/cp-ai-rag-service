package uk.gov.moj.cp.retrieval.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class EmbedDataServiceTest {

    @Mock
    private EmbeddingService mockEmbeddingService;

    private EmbedDataService embedDataService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        embedDataService = new EmbedDataService(mockEmbeddingService);
    }

    @Test
    void getEmbedding_ReturnsEmbeddings_WhenDataIsValid() throws EmbeddingServiceException {
        when(mockEmbeddingService.embedData("valid data")).thenReturn(List.of(0.1f, 0.2f, 0.3f));

        List<Float> embeddings = embedDataService.getEmbedding("valid data");

        assertEquals(List.of(0.1f, 0.2f, 0.3f), embeddings);
    }

    @Test
    void getEmbedding_ReturnsEmptyEmbeddings_WhenEmbeddingsAreNull() throws EmbeddingServiceException {
        when(mockEmbeddingService.embedData("data")).thenReturn(null);

        final List<Float> embeddings = embedDataService.getEmbedding("data");

        assertEquals(0, embeddings.size());
    }

    @Test
    void getEmbedding_ReturnsEmptyEmbeddings_WhenEmbeddingsAreEmpty() throws EmbeddingServiceException {
        when(mockEmbeddingService.embedData("data")).thenReturn(List.of());

        final List<Float> embeddings = embedDataService.getEmbedding("data");

        assertEquals(0, embeddings.size());
    }

    @Test
    void getEmbedding_ReturnsEmptyEmbeddings_WhenEmbeddingServiceThrowsException() throws EmbeddingServiceException {
        when(mockEmbeddingService.embedData("data")).thenThrow(EmbeddingServiceException.class);

        final List<Float> embeddings = embedDataService.getEmbedding("data");

        assertEquals(0, embeddings.size());
    }
}
