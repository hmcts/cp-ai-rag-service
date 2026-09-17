package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.exception.EmbeddingServiceException;

import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Contract for the openai-java implementation of {@link EmbeddingService} (FR-5; AC-8…AC-12).
 *
 * <p>Mirrors {@code AzureEmbeddingServiceTest}'s five scenarios on the new SDK plus the two
 * guarantees the Azure implementation only holds implicitly: explicit input-order restoration via
 * {@code Embedding.index()} (AC-8) and the request parameters (model / user tag, AC-12).</p>
 *
 * <p>Injection seam: the package-visible {@code protected OpenAiEmbeddingService(OpenAIClient,
 * String)} constructor, exactly as {@code AzureEmbeddingServiceTest} injects its Azure client —
 * no reflection.</p>
 */
class OpenAiEmbeddingServiceTest {

    private static final String DEPLOYMENT_NAME = "deploymentName";
    private static final String USER_TAG = "cp-ai-document-rag-embedding-service";

    @Test
    @DisplayName("AC-8: returns vectors in input order even when the SDK returns them out of index order, in one call")
    void embedCollectionDataRestoresInputOrderFromShuffledIndexes() throws EmbeddingServiceException {
        final List<Float> vectorForInput0 = List.of(0.0f, 0.1f);
        final List<Float> vectorForInput1 = List.of(1.0f, 1.1f);
        final List<Float> vectorForInput2 = List.of(2.0f, 2.1f);

        // Deliberately shuffled: array position 0 carries index 2, position 1 carries index 0, etc.
        // A implementation that trusts array order fails here; only an index-sort passes.
        final CreateEmbeddingResponse response = embeddingResponse(
                embedding(vectorForInput2, 2L),
                embedding(vectorForInput0, 0L),
                embedding(vectorForInput1, 1L));

        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class))).thenReturn(response);

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);
        final List<List<Float>> results = service.embedCollectionData(List.of("input0", "input1", "input2"));

        assertNotNull(results);
        assertEquals(3, results.size());
        assertEquals(vectorForInput0, results.get(0));
        assertEquals(vectorForInput1, results.get(1));
        assertEquals(vectorForInput2, results.get(2));

        // AC-8: exactly one SDK request for the whole batch.
        verify(mockClient.embeddings()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("AC-9: returns an empty list and does not throw when the response carries no data")
    void embedCollectionDataReturnsEmptyListWhenNoEmbeddingData() throws EmbeddingServiceException {
        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class)))
                .thenReturn(embeddingResponse());

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);
        final List<List<Float>> results = service.embedCollectionData(List.of("content"));

        assertNotNull(results);
        assertTrue(results.isEmpty(), "empty data() must yield an empty list, not an exception");
        verify(mockClient.embeddings()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("AC-10: wraps any SDK exception in EmbeddingServiceException, preserving the cause")
    void embedCollectionDataWrapsSdkExceptionInEmbeddingServiceException() {
        final RuntimeException sdkFailure = new RuntimeException("Embedding failed");
        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class))).thenThrow(sdkFailure);

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);

        final EmbeddingServiceException exception = assertThrows(EmbeddingServiceException.class,
                () -> service.embedCollectionData(List.of("content")));
        assertEquals("Failed to embed content", exception.getMessage());
        assertSame(sdkFailure, exception.getCause(), "no SDK exception type may leak to callers");
    }

    @Test
    @DisplayName("AC-11: throws IllegalArgumentException for a null or empty content list")
    void embedCollectionDataThrowsForNullOrEmptyList() {
        final OpenAiEmbeddingService service =
                new OpenAiEmbeddingService(mock(OpenAIClient.class), DEPLOYMENT_NAME);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedCollectionData(null));
        assertEquals("Content list cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedCollectionData(List.of()));
        assertEquals("Content list cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when the content to embed is null or empty")
    void embedDataThrowsExceptionForNullOrEmptyContent() {
        final OpenAiEmbeddingService service =
                new OpenAiEmbeddingService(mock(OpenAIClient.class), DEPLOYMENT_NAME);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedData(null));
        assertEquals("Content to embed cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> service.embedData(""));
        assertEquals("Content to embed cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("embedData delegates to embedCollectionData and returns the first vector")
    void embedDataReturnsFirstVectorForValidContent() throws EmbeddingServiceException {
        final List<Float> expected = List.of(0.1f, 0.2f, 0.3f);
        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class)))
                .thenReturn(embeddingResponse(embedding(expected, 0L)));

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);
        final List<Float> result = service.embedData("content");

        assertEquals(expected, result);
        verify(mockClient.embeddings()).create(any(EmbeddingCreateParams.class));
    }

    @Test
    @DisplayName("embedData returns an empty list when the response carries no data")
    void embedDataReturnsEmptyListWhenNoEmbeddingData() throws EmbeddingServiceException {
        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class)))
                .thenReturn(embeddingResponse());

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);
        final List<Float> result = service.embedData("content");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("AC-12: request carries the deployment name as model, the input batch, and the service user tag")
    void requestParametersCarryDeploymentNameAndUserTag() throws EmbeddingServiceException {
        final OpenAIClient mockClient = mock(OpenAIClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.embeddings().create(any(EmbeddingCreateParams.class)))
                .thenReturn(embeddingResponse(embedding(List.of(0.1f), 0L), embedding(List.of(0.2f), 1L)));

        final OpenAiEmbeddingService service = new OpenAiEmbeddingService(mockClient, DEPLOYMENT_NAME);
        service.embedCollectionData(List.of("first", "second"));

        final ArgumentCaptor<EmbeddingCreateParams> captor = ArgumentCaptor.forClass(EmbeddingCreateParams.class);
        verify(mockClient.embeddings()).create(captor.capture());
        final EmbeddingCreateParams params = captor.getValue();

        assertEquals(DEPLOYMENT_NAME, params.model().asString(),
                "model must be the configured Azure deployment name, passed through verbatim");
        assertEquals(List.of("first", "second"), params.input().asArrayOfStrings(),
                "the whole batch must go out as one array-of-strings input");
        assertEquals(USER_TAG, params.user().orElse(null),
                "the abuse-monitoring user tag must match the Azure implementation's value");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when the endpoint is null or empty")
    void throwsExceptionWhenEndpointIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiEmbeddingService((String) null, DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable for embedding service must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiEmbeddingService("", DEPLOYMENT_NAME));
        assertEquals("Endpoint environment variable for embedding service must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Throws IllegalArgumentException when the deployment name is null or empty")
    void throwsExceptionWhenDeploymentNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiEmbeddingService("https://example.openai.azure.com", null));
        assertEquals("Deployment name environment variable for embedding service must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new OpenAiEmbeddingService("https://example.openai.azure.com", ""));
        assertEquals("Deployment name environment variable for embedding service must be set.", exception.getMessage());
    }

    private static Embedding embedding(final List<Float> vector, final long index) {
        return Embedding.builder()
                .embedding(vector)
                .index(index)
                .build();
    }

    private static CreateEmbeddingResponse embeddingResponse(final Embedding... embeddings) {
        return CreateEmbeddingResponse.builder()
                .data(List.of(embeddings))
                .model(DEPLOYMENT_NAME)
                .usage(CreateEmbeddingResponse.Usage.builder()
                        .promptTokens(0L)
                        .totalTokens(0L)
                        .build())
                .build();
    }
}
