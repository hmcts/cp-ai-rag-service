package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static com.microsoft.azure.functions.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.microsoft.azure.functions.HttpStatus.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;

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
class InitiateAnswerGenerationFunctionTest {

    @Mock
    private AnswerGenerationTableService answerGenerationTableService;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private HttpRequestMessage<AnswerUserQueryRequest> mockRequest;

    @Mock
    private OutputBinding<String> outputBinding;

    @InjectMocks
    private InitiateAnswerGenerationFunction function;

    @Mock
    private HttpResponseMessage.Builder mockResponseBuilder;

    @Mock
    private HttpResponseMessage mockResponse;

    @Test
    void run_shouldReturnBadRequest_whenPayloadIsInvalid() {
        final AnswerUserQueryRequest payload = new AnswerUserQueryRequest(null, "prompt", List.of(new MetadataFilter("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        mockHttpResponse(BAD_REQUEST);

        final HttpResponseMessage result = function.run(mockRequest, outputBinding, executionContext);

        assertEquals(BAD_REQUEST, result.getStatus());
        verifyNoInteractions(outputBinding);
        verifyNoInteractions(answerGenerationTableService);
    }

    @Test
    void run_shouldReturnOk_andWriteToQueueAndTable() throws DuplicateRecordException {
        final String userQuery = "user query";
        final String queryPrompt = "query prompt";
        final AnswerUserQueryRequest payload = new AnswerUserQueryRequest(userQuery, queryPrompt, List.of(new MetadataFilter("key", "value")));
        when(mockRequest.getBody()).thenReturn(payload);
        mockHttpResponse(OK);

        final HttpResponseMessage result = function.run(mockRequest, outputBinding, executionContext);

        assertEquals(OK, result.getStatus());
        verify(outputBinding).setValue(anyString());
        verify(answerGenerationTableService).saveAnswerGenerationRequest(anyString(),
                eq(userQuery), eq(queryPrompt),
                eq(ANSWER_GENERATION_PENDING));

        verify(mockRequest).createResponseBuilder(OK);
        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).body(argThat(
                json().at("/transactionId").isNotNull()
                        .toArgumentMatcher()
        ));
    }

    @Test
    void run_shouldReturnInternalServerError_whenExceptionOccurs() {
        when(mockRequest.getBody()).thenThrow(new RuntimeException("boom"));
        mockHttpResponse(INTERNAL_SERVER_ERROR);

        final HttpResponseMessage result = function.run(mockRequest, outputBinding, executionContext);

        assertEquals(INTERNAL_SERVER_ERROR, result.getStatus());
        verifyNoInteractions(outputBinding);
        verifyNoInteractions(answerGenerationTableService);
    }

    private void mockHttpResponse(final HttpStatus expectedStatus) {
        when(mockRequest.createResponseBuilder(any(HttpStatus.class))).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);
        when(mockResponse.getStatus()).thenReturn(expectedStatus);
    }

}