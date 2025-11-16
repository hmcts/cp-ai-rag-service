package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.io.IOException;
import java.util.List;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.json.JsonOptions;
import com.azure.json.JsonReader;
import com.azure.json.implementation.DefaultJsonReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingServiceTest {

    @Test
    @DisplayName("Throws exception when content to embed is null or empty")
    void embedStringDataThrowsExceptionForNullOrEmptyContent() {
        EmbeddingService service = new EmbeddingService(mock(OpenAIClient.class), "deploymentName");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedStringData(null));
        assertEquals("Content to embed cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedStringData(""));
        assertEquals("Content to embed cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Returns empty list when no embedding data is returned")
    void embedStringDataReturnsEmptyListWhenNoEmbeddingData() throws EmbeddingServiceException, IOException {
        JsonReader jsonReader = DefaultJsonReader.fromString("{\"data\":[],\"usage\":null}", new JsonOptions());
        Embeddings embeddings = Embeddings.fromJson(jsonReader);

        OpenAIClient mockClient = mock(OpenAIClient.class);
        when(mockClient.getEmbeddings(any(String.class), any(EmbeddingsOptions.class)))
                .thenReturn(embeddings);

        EmbeddingService service = new EmbeddingService(mockClient, "deploymentName");
        List<Float> result = service.embedStringData("content");

        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    @DisplayName("Returns embedding data for valid content")
    void embedStringDataReturnsEmbeddingForValidContent() throws EmbeddingServiceException, IOException {
        OpenAIClient mockClient = mock(OpenAIClient.class);
        String embeddingJson = """
                {
                  "data": [
                    { "embedding": [0.1, 0.2, 0.3] }
                  ],
                  "usage": null
                }
                """;
        JsonReader jsonReader = DefaultJsonReader.fromString(embeddingJson, new JsonOptions());
        Embeddings embeddings = Embeddings.fromJson(jsonReader);

        List<Float> mockEmbedding = List.of(0.1f, 0.2f, 0.3f);
        when(mockClient.getEmbeddings(any(String.class), any(EmbeddingsOptions.class))).thenReturn(embeddings);

        EmbeddingService service = new EmbeddingService(mockClient, "deploymentName");
        List<Float> result = service.embedStringData("content");

        assertNotNull(result);
        assertEquals(mockEmbedding, result);
    }

    @Test
    @DisplayName("Throws EmbeddingServiceException when embedding fails")
    void embedStringDataThrowsExceptionWhenEmbeddingFails() {
        OpenAIClient mockClient = mock(OpenAIClient.class);
        when(mockClient.getEmbeddings(any(String.class), any(EmbeddingsOptions.class)))
                .thenThrow(new RuntimeException("Embedding failed"));

        EmbeddingService service = new EmbeddingService(mockClient, "deploymentName");

        EmbeddingServiceException exception = assertThrows(EmbeddingServiceException.class,
                () -> service.embedStringData("content"));
        assertEquals("Failed to embed content", exception.getMessage());
    }
}
