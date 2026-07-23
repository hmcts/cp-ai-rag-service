package uk.gov.moj.cp.retrieval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.retrieval.model.CitationGuardMode.DELIVER;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Enforcement wiring for the synchronous answer endpoint: a rejected identity is a 401 with no
 * search performed; a resolved identity is threaded into the search scope and the scoring payload;
 * only the header-derived identity is used.
 */
@ExtendWith(MockitoExtension.class)
class SyncAnswerGenerationFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SPOOF_ID = "99999999-9999-9999-9999-999999999999";

    @Mock
    private EmbedDataService embedDataService;
    @Mock
    private AzureAISearchService searchService;
    @Mock
    private ResponseGenerationService responseGenerationService;
    @Mock
    private BlobPersistenceService blobPersistenceService;
    @Mock
    private ClientIdentityResolver clientIdentityResolver;
    @Mock
    private HttpRequestMessage<AnswerUserQueryRequest> request;
    @Mock
    private OutputBinding<String> outputBinding;
    @Mock
    private HttpResponseMessage.Builder responseBuilder;
    @Mock
    private HttpResponseMessage response;
    @Mock
    private ExecutionContext context;

    private SyncAnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        function = new SyncAnswerGenerationFunction(embedDataService, searchService, responseGenerationService,
                blobPersistenceService, DELIVER, clientIdentityResolver);
    }

    @Test
    @DisplayName("returns 401 and performs no search when the client identity is rejected")
    void shouldReturnUnauthorised_whenIdentityRejected() throws Exception {
        when(clientIdentityResolver.resolve(request)).thenThrow(new ClientIdentityException("missing or invalid client identity"));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(request).createResponseBuilder(HttpStatus.UNAUTHORIZED);
        verify(searchService, never()).search(any(), any(), any(), any());
    }

    @Test
    @DisplayName("threads the resolved client id into the search scope")
    void shouldThreadClientId_intoSearch() throws Exception {
        stubHappyPath();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));

        function.run(request, outputBinding, context);

        verify(searchService).search(eq(CLIENT_ID), eq("query"), any(), any());
    }

    @Test
    @DisplayName("stamps the resolved client id onto the scoring payload")
    void shouldStampClientId_ontoScoringPayload() throws Exception {
        stubHappyPath();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));

        function.run(request, outputBinding, context);

        verify(blobPersistenceService).saveBlob(anyString(),
                argThat(json().at("/clientId").isText(CLIENT_ID).toArgumentMatcher()));
    }

    @Test
    @DisplayName("uses only the header identity — a clientId-shaped value in metadataFilter has no effect on the search scope")
    void shouldUseHeaderIdentityOnly_whenMetadataCarriesSpoof() throws Exception {
        final List<MetadataFilter> metadataFilter = List.of(new MetadataFilter("document_id", SPOOF_ID));
        when(request.getBody()).thenReturn(new AnswerUserQueryRequest("query", "prompt", metadataFilter));
        when(embedDataService.getEmbedding("query")).thenReturn(List.of(1.0f));
        when(searchService.search(any(), eq("query"), any(), any())).thenReturn(chunk());
        when(responseGenerationService.generateResponse(eq("query"), any(), eq("prompt")))
                .thenReturn(new LlmResponse("raw", "generated", ANSWER_GENERATED));
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(searchService).search(eq(CLIENT_ID), eq("query"), any(), any());
        verify(searchService, never()).search(eq(SPOOF_ID), any(), any(), any());
    }

    @Test
    @DisplayName("flag off (default resolver) keeps the legacy null-scoped search")
    void shouldSearchWithoutScope_whenEnforcementOff() throws Exception {
        final SyncAnswerGenerationFunction defaultFunction = new SyncAnswerGenerationFunction(
                embedDataService, searchService, responseGenerationService, blobPersistenceService, DELIVER, null);
        stubHappyPath();

        defaultFunction.run(request, outputBinding, context);

        verify(searchService).search(isNull(), eq("query"), any(), any());
    }

    private void stubHappyPath() throws Exception {
        when(request.getBody()).thenReturn(new AnswerUserQueryRequest("query", "prompt", List.of(new MetadataFilter("key", "value"))));
        when(embedDataService.getEmbedding("query")).thenReturn(List.of(1.0f));
        when(searchService.search(any(), eq("query"), any(), any())).thenReturn(chunk());
        when(responseGenerationService.generateResponse(eq("query"), any(), eq("prompt")))
                .thenReturn(new LlmResponse("raw", "generated", ANSWER_GENERATED));
        mockResponse();
    }

    private List<ChunkedEntry> chunk() {
        return List.of(ChunkedEntry.builder().id("1").chunk("content").documentFileName("doc.pdf")
                .pageNumber(1).documentId("doc1").build());
    }

    private void mockResponse() {
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(response);
    }
}
