package uk.gov.moj.cp.retrieval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;

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
 * Enforcement wiring for the async initiate endpoint: a rejected identity is a 401 with nothing
 * enqueued or persisted; a resolved identity is copied onto the queue payload and the pending row.
 */
@ExtendWith(MockitoExtension.class)
class InitiateAnswerGenerationFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SPOOF_ID = "99999999-9999-9999-9999-999999999999";

    @Mock
    private AnswerGenerationTableService answerGenerationTableService;
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

    private InitiateAnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        function = new InitiateAnswerGenerationFunction(answerGenerationTableService, clientIdentityResolver);
    }

    @Test
    @DisplayName("returns 401 and neither enqueues nor persists when the client identity is rejected")
    void shouldReturnUnauthorised_whenIdentityRejected() throws Exception {
        when(clientIdentityResolver.resolve(request)).thenThrow(new ClientIdentityException("missing or invalid client identity"));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(request).createResponseBuilder(HttpStatus.UNAUTHORIZED);
        verify(outputBinding, never()).setValue(anyString());
        verify(answerGenerationTableService, never()).saveAnswerGenerationRequest(any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("copies the resolved client id onto the queue payload")
    void shouldCopyClientId_ontoQueuePayload() {
        when(request.getBody()).thenReturn(validRequest());
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(outputBinding).setValue(argThat(json().at("/clientId").isText(CLIENT_ID).toArgumentMatcher()));
    }

    @Test
    @DisplayName("persists the pending row under the resolved client id")
    void shouldPersistPendingRow_underResolvedClientId() throws Exception {
        when(request.getBody()).thenReturn(validRequest());
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(answerGenerationTableService).saveAnswerGenerationRequest(eq(CLIENT_ID), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("uses only the header identity — a clientId-shaped metadataFilter value has no effect")
    void shouldUseHeaderIdentityOnly_whenMetadataCarriesSpoof() throws Exception {
        when(request.getBody()).thenReturn(new AnswerUserQueryRequest("query", "prompt",
                List.of(new MetadataFilter("document_id", SPOOF_ID))));
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        mockResponse();

        function.run(request, outputBinding, context);

        verify(answerGenerationTableService).saveAnswerGenerationRequest(eq(CLIENT_ID), anyString(), any(), any(), any());
        verify(answerGenerationTableService, never()).saveAnswerGenerationRequest(eq(SPOOF_ID), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("flag off (default resolver) keeps the legacy null-scoped pending row")
    void shouldPersistLegacyRow_whenEnforcementOff() throws Exception {
        final InitiateAnswerGenerationFunction defaultFunction =
                new InitiateAnswerGenerationFunction(answerGenerationTableService, null);
        when(request.getBody()).thenReturn(validRequest());
        mockResponse();

        defaultFunction.run(request, outputBinding, context);

        verify(answerGenerationTableService).saveAnswerGenerationRequest(isNull(), anyString(), any(), any(), any());
    }

    private AnswerUserQueryRequest validRequest() {
        return new AnswerUserQueryRequest("query", "prompt", List.of(new MetadataFilter("key", "value")));
    }

    private void mockResponse() {
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(response);
    }
}
