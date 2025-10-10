package uk.gov.moj.cp.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.model.RequestPayload;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;
import uk.gov.moj.cp.retrieval.service.SearchService;

import java.util.List;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AnswerRetrievalFunctionTest {

    @Mock
    private HttpRequestMessage<RequestPayload> mockRequest;

    @Mock
    private ExecutionContext mockContext;

    @Mock
    private OutputBinding<String> mockOutputBinding;

    @Mock
    private EmbedDataService mockEmbedDataService;

    @Mock
    private SearchService mockSearchService;

    @Mock
    private ResponseGenerationService mockResponseGenerationService;

    @Mock
    private HttpResponseMessage.Builder mockResponseBuilder;

    private AnswerRetrievalFunction function;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        function = new AnswerRetrievalFunction(mockEmbedDataService, mockSearchService, mockResponseGenerationService);
    }

    @Test
    void run_ReturnsBadRequest_WhenUserQueryIsNull() {
        RequestPayload payload = new RequestPayload(null, "prompt", List.of(new KeyValuePair("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        when(mockRequest.createResponseBuilder(HttpStatus.BAD_REQUEST)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(anyString())).thenReturn(mockResponseBuilder);
        final HttpResponseMessage mockResponse = mock(HttpResponseMessage.class);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);

        HttpResponseMessage response = function.run(mockRequest, mockOutputBinding, mockContext);

        assertEquals(mockResponse, response);
        verify(mockRequest).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(json().at("/errorMessage").isText("Error: userQuery, queryPrompt and metadataFilter attributes are required").toArgumentMatcher()));

    }

    @Test
    void run_ReturnsBadRequest_WhenMetadataFilterIsEmpty() {
        RequestPayload payload = new RequestPayload("doc1", "prompt", List.of());
        when(mockRequest.getBody()).thenReturn(payload);
        when(mockRequest.createResponseBuilder(HttpStatus.BAD_REQUEST)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(anyString())).thenReturn(mockResponseBuilder);
        final HttpResponseMessage mockResponse = mock(HttpResponseMessage.class);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);

        HttpResponseMessage response = function.run(mockRequest, mockOutputBinding, mockContext);

        assertEquals(mockResponse, response);
        verify(mockRequest).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(json().at("/errorMessage").isText("Error: userQuery, queryPrompt and metadataFilter attributes are required").toArgumentMatcher()));
    }

    @Test
    void run_ReturnsOk_WhenValidRequestIsProvided() {
        final List<KeyValuePair> metadataFilter = List.of(new KeyValuePair("key", "value"));
        RequestPayload payload = new RequestPayload("query", "prompt", metadataFilter);

        final List<Double> mockEmbeddings = List.of(1.0, 2.0);
        final List<ChunkedEntry> mockSearchDocuments = List.of(ChunkedEntry.builder()
                .id("1")
                .chunk("Sample content")
                .documentFileName("doc file name")
                .pageNumber(5)
                .documentId("doc1 id")
                .build());

        when(mockEmbedDataService.getEmbedding("query")).thenReturn(mockEmbeddings);
        when(mockSearchService.searchDocumentsMatchingFilterCriteria(eq("query"), eq(mockEmbeddings), eq(metadataFilter))).thenReturn(mockSearchDocuments);
        when(mockResponseGenerationService.generateResponse("query", mockSearchDocuments, "prompt")).thenReturn("generated response");

        when(mockRequest.getBody()).thenReturn(payload);
        when(mockRequest.createResponseBuilder(HttpStatus.OK)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(anyString())).thenReturn(mockResponseBuilder);
        final HttpResponseMessage mockResponse = mock(HttpResponseMessage.class);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);

        function.run(mockRequest, mockOutputBinding, mockContext);
        verify(mockRequest).createResponseBuilder(HttpStatus.OK);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(
                json().at("/userQuery").isText("query")
                        .at("/llmResponse").isText("generated response")
                        .at("/queryPrompt").isText("prompt")
                        .at("/chunkedEntries").isArray()
                        .toArgumentMatcher()
        ));
    }

    @Test
    void run_ReturnsInternalServerError_OnException() {

        RequestPayload payload = new RequestPayload("query", "prompt", List.of(new KeyValuePair("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        when(mockEmbedDataService.getEmbedding("query")).thenThrow(new RuntimeException("Test exception"));
        when(mockRequest.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(anyString())).thenReturn(mockResponseBuilder);

        function.run(mockRequest, mockOutputBinding, mockContext);

        verify(mockRequest).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(json().at("/errorMessage").isText("An internal error occurred: Test exception").toArgumentMatcher()));
    }
}
