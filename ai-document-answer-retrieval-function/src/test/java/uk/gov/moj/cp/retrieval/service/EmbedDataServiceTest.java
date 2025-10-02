package uk.gov.moj.cp.retrieval.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.moj.cp.ai.service.EmbeddingService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void getEmbedding_ReturnsEmbeddings_WhenDataIsValid() {
        when(mockEmbeddingService.embedStringData("valid data")).thenReturn(List.of(0.1, 0.2, 0.3));

        List<Double> embeddings = embedDataService.getEmbedding("valid data");

        assertEquals(List.of(0.1, 0.2, 0.3), embeddings);
    }

    @Test
    void getEmbedding_ThrowsException_WhenEmbeddingsAreNull() {
        when(mockEmbeddingService.embedStringData("data")).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> embedDataService.getEmbedding("data"));
    }

    @Test
    void getEmbedding_ThrowsException_WhenEmbeddingsAreEmpty() {
        when(mockEmbeddingService.embedStringData("data")).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> embedDataService.getEmbedding("data"));
    }
}
