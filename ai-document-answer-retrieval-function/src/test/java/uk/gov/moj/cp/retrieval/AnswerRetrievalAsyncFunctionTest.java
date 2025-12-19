package uk.gov.moj.cp.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.model.RequestPayload;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;

import java.util.List;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerRetrievalAsyncFunctionTest {

    @Mock
    private AnswerGenerationTableStorageService answerGenerationTableStorageService;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private HttpRequestMessage<RequestPayload> request;

    @Mock
    private OutputBinding<String> outputBinding;

    @InjectMocks
    private AnswerRetrievalAsyncFunction function;

    @Mock
    private RequestPayload payload;

    @Mock
    private HttpResponseMessage.Builder builder;
    @Mock
    private HttpResponseMessage response;

    @Test
    void run_shouldReturnBadRequest_whenPayloadIsInvalid() {

        when(payload.userQuery()).thenReturn(null);
        when(request.getBody()).thenReturn(payload);
        mockHttpResponse(HttpStatus.BAD_REQUEST);

        final HttpResponseMessage result = function.run(request, outputBinding, executionContext);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatus());

        verifyNoInteractions(outputBinding);
        verifyNoInteractions(answerGenerationTableStorageService);
    }

    @Test
    void run_shouldReturnOk_andWriteToQueueAndTable() throws DuplicateRecordException {
        final String userQuery = "user query";
        final String queryPrompt = "query prompt";
        when(payload.userQuery()).thenReturn(userQuery);
        when(payload.queryPrompt()).thenReturn(queryPrompt);
        when(payload.metadataFilter()).thenReturn(List.of(new KeyValuePair("k", "v")));
        when(request.getBody()).thenReturn(payload);

        mockHttpResponse(HttpStatus.OK);

        final HttpResponseMessage result = function.run(request, outputBinding, executionContext);

        assertEquals(HttpStatus.OK, result.getStatus());

        verify(outputBinding).setValue(anyString());

        verify(answerGenerationTableStorageService).insertIntoTable(anyString(),
                eq(userQuery), eq(queryPrompt),
                eq(ANSWER_GENERATION_PENDING));
    }

    @Test
    void run_shouldReturnInternalServerError_whenExceptionOccurs() {
        when(request.getBody()).thenThrow(new RuntimeException("boom"));

        mockHttpResponse(HttpStatus.INTERNAL_SERVER_ERROR);

        final HttpResponseMessage result = function.run(request, outputBinding, executionContext);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatus());

        verifyNoInteractions(outputBinding);
        verifyNoInteractions(answerGenerationTableStorageService);
    }

    private void mockHttpResponse(final HttpStatus expectedStatus) {
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.body(any())).thenReturn(builder);
        when(builder.build()).thenReturn(response);
        when(response.getStatus()).thenReturn(expectedStatus);
    }

}