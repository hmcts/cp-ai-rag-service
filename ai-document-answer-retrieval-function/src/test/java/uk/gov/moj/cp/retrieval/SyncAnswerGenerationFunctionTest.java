package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static com.microsoft.azure.functions.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.exception.SearchServiceException;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

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

class SyncAnswerGenerationFunctionTest {

    @Mock
    private HttpRequestMessage<AnswerUserQueryRequest> mockRequest;

    @Mock
    private ExecutionContext mockContext;

    @Mock
    private OutputBinding<String> mockOutputBinding;

    @Mock
    private EmbedDataService mockEmbedDataService;

    @Mock
    private AzureAISearchService mockSearchService;

    @Mock
    private ResponseGenerationService mockResponseGenerationService;

    @Mock
    private BlobPersistenceService mockBlobPersistenceService;

    @Mock
    private HttpResponseMessage.Builder mockResponseBuilder;

    @Mock
    private HttpResponseMessage mockResponse;

    private SyncAnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        function = new SyncAnswerGenerationFunction(mockEmbedDataService, mockSearchService, mockResponseGenerationService, mockBlobPersistenceService);
    }

    @Test
    void shouldReturnBadRequest_WhenValidationFails() {
        AnswerUserQueryRequest payload = new AnswerUserQueryRequest(null, "prompt", List.of(new MetadataFilter("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        mockHttpResponse(BAD_REQUEST);

        HttpResponseMessage response = function.run(mockRequest, mockOutputBinding, mockContext);

        assertEquals(mockResponse, response);
        verify(mockRequest).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(json().at("/errorMessage").textContains("userQuery").toArgumentMatcher()));

    }

    @Test
    void run_ReturnsOk_WhenValidRequestIsProvided() throws SearchServiceException {
        final List<MetadataFilter> metadataFilter = List.of(new MetadataFilter("key", "value"));
        AnswerUserQueryRequest payload = new AnswerUserQueryRequest("query", "prompt", metadataFilter);

        final List<Float> mockEmbeddings = List.of(1.0f, 2.0f);
        final List<ChunkedEntry> mockSearchDocuments = List.of(ChunkedEntry.builder()
                .id("1")
                .chunk("Sample content")
                .documentFileName("doc file name")
                .pageNumber(5)
                .documentId("doc1 id")
                .customMetadata(List.of(new KeyValuePair("key1", "value1"), new KeyValuePair("key2", "value2")))
                .build());

        when(mockEmbedDataService.getEmbedding("query")).thenReturn(mockEmbeddings);
        when(mockSearchService.search(eq("query"), eq(mockEmbeddings), eq(convertToKeyValuePair(metadataFilter)))).thenReturn(mockSearchDocuments);
        when(mockResponseGenerationService.generateResponse("query", mockSearchDocuments, "prompt")).thenReturn("generated response");
        when(mockRequest.getBody()).thenReturn(payload);
        mockHttpResponse(OK);

        function.run(mockRequest, mockOutputBinding, mockContext);
        verify(mockRequest).createResponseBuilder(HttpStatus.OK);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(
                json().at("/userQuery").isText("query")
                        .at("/llmResponse").isText("generated response")
                        .at("/queryPrompt").isText("prompt")
                        .at("/documentChunks").isArray()
                        .at("/documentChunks/0/documentId").isText("doc1 id")
                        .at("/documentChunks/0/documentName").isText("doc file name")
                        .at("/documentChunks/0/pageNumber").isNumberEqualTo(5)
                        .at("/documentChunks/0/chunkContent").isText("Sample content")
                        .at("/documentChunks/0/customMetadata/0/key").isText("key1")
                        .toArgumentMatcher()
        ));
        verify(mockBlobPersistenceService).saveBlob(anyString(), argThat(
                json().at("/userQuery").isText("query")
                        .at("/llmResponse").isText("generated response")
                        .at("/queryPrompt").isText("prompt")
                        .at("/chunkedEntries").isArray()
                        .at("/transactionId").isNull()
                        .toArgumentMatcher()));

        verify(mockOutputBinding).setValue(argThat(
                json().at("/filename").matches("^llm-answer-with-chunks-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.json$")
                        .toArgumentMatcher()));
    }

    @Test
    void run_ReturnsInternalServerError_OnException() {

        AnswerUserQueryRequest payload = new AnswerUserQueryRequest("query", "prompt", List.of(new MetadataFilter("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        when(mockEmbedDataService.getEmbedding("query")).thenThrow(new RuntimeException("Test exception"));
        mockHttpResponse(INTERNAL_SERVER_ERROR);

        function.run(mockRequest, mockOutputBinding, mockContext);

        verify(mockRequest).createResponseBuilder(INTERNAL_SERVER_ERROR);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(json().at("/errorMessage").isText("An internal error occurred: Test exception").toArgumentMatcher()));
    }

    private List<KeyValuePair> convertToKeyValuePair(final List<MetadataFilter> metadataFilter) {
        return metadataFilter.stream().map(mf -> new KeyValuePair(mf.getKey(), mf.getValue())).toList();
    }

    private void mockHttpResponse(final HttpStatus expectedStatus) {
        when(mockRequest.createResponseBuilder(eq(expectedStatus))).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);
        when(mockResponse.getStatus()).thenReturn(expectedStatus);
    }
}
