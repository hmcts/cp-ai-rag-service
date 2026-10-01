package uk.gov.moj.cp.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;
import static uk.gov.moj.cp.retrieval.model.CitationGuardMode.DELIVER;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/**
 * The synchronous endpoint has no transaction: inv= is its correlation key, and it is forwarded
 * to the scoring blob as {@code originInvocationId} so the scoring run can be read as the same journey.
 */
class SyncAnswerGenerationFunctionLogContextTest {

    @SuppressWarnings("unchecked")
    private final HttpRequestMessage<AnswerUserQueryRequest> request = mock(HttpRequestMessage.class);
    private final HttpResponseMessage.Builder responseBuilder = mock(HttpResponseMessage.Builder.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    @SuppressWarnings("unchecked")
    private final OutputBinding<String> scoringMessage = mock(OutputBinding.class);
    private final EmbedDataService embedDataService = mock(EmbedDataService.class);
    private final AzureAISearchService searchService = mock(AzureAISearchService.class);
    private final ResponseGenerationService responseGenerationService = mock(ResponseGenerationService.class);
    private final BlobPersistenceService blobPersistenceService = mock(BlobPersistenceService.class);

    private SyncAnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-sync");
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
        function = new SyncAnswerGenerationFunction(embedDataService, searchService, responseGenerationService,
                blobPersistenceService, DELIVER, null);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("inv= is on the thread during the pipeline, no txn= exists, and the scope closes on return")
    void pipelineRunsWithInvocationIdOnly_andScopeCloses() throws Exception {
        stubHappyPath();
        final Map<String, String> seenByEmbedding = new HashMap<>();
        when(embedDataService.getEmbedding("query")).thenAnswer(invocation -> {
            seenByEmbedding.putAll(MDC.getCopyOfContextMap());
            return List.of(1.0f);
        });

        function.run(request, scoringMessage, context);

        assertEquals("inv-sync", seenByEmbedding.get(INVOCATION_ID));
        assertNull(seenByEmbedding.get(TRANSACTION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }

    @Test
    @DisplayName("the scoring blob carries the invocation id as originInvocationId, with transactionId null")
    void scoringPayloadCarriesOriginInvocationId() throws Exception {
        stubHappyPath();
        when(embedDataService.getEmbedding("query")).thenReturn(List.of(1.0f));

        function.run(request, scoringMessage, context);

        final ArgumentCaptor<String> blobJson = ArgumentCaptor.forClass(String.class);
        verify(blobPersistenceService).saveBlob(anyString(), blobJson.capture());
        final ScoringPayload saved = new ObjectMapper().readValue(blobJson.getValue(), ScoringPayload.class);
        assertEquals("inv-sync", saved.originInvocationId());
        assertNull(saved.transactionId());
    }

    private void stubHappyPath() throws Exception {
        when(request.getBody()).thenReturn(new AnswerUserQueryRequest("query", "prompt", List.of(new MetadataFilter("key", "value"))));
        final List<ChunkedEntry> chunks = List.of(ChunkedEntry.builder().id("1").chunk("c").documentId("d").documentFileName("f").pageNumber(1).build());
        when(searchService.search(isNull(), eq("query"), any(), any())).thenReturn(chunks);
        when(responseGenerationService.generateResponse("query", chunks, "prompt"))
                .thenReturn(new LlmResponse("raw", "formatted", ANSWER_GENERATED));
    }
}
