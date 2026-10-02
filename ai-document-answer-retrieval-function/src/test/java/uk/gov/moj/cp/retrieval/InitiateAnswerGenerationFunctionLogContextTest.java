package uk.gov.moj.cp.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import uk.gov.hmcts.cp.openapi.model.AnswerUserQueryRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InitiateAnswerGenerationFunctionLogContextTest {

    @SuppressWarnings("unchecked")
    private final HttpRequestMessage<AnswerUserQueryRequest> request = mock(HttpRequestMessage.class);
    private final HttpResponseMessage.Builder responseBuilder = mock(HttpResponseMessage.Builder.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    @SuppressWarnings("unchecked")
    private final OutputBinding<String> queueMessage = mock(OutputBinding.class);
    private final AnswerGenerationTableService tableService = mock(AnswerGenerationTableService.class);

    private InitiateAnswerGenerationFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-initiate");
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
        function = new InitiateAnswerGenerationFunction(tableService, null);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("the generated transaction id is on the thread when the pending row is written, and gone after run")
    void generatedTransactionIdIsOnThreadForPendingRowWrite() throws Exception {
        when(request.getBody()).thenReturn(new AnswerUserQueryRequest("query", "prompt", List.of(new MetadataFilter("key", "value"))));
        final AtomicReference<String> txnOnThread = new AtomicReference<>();
        final AtomicReference<String> txnWritten = new AtomicReference<>();
        doAnswer(invocation -> {
            txnOnThread.set(MDC.get(TRANSACTION_ID));
            txnWritten.set(invocation.getArgument(1));
            return null;
        }).when(tableService).saveAnswerGenerationRequest(any(), anyString(), anyString(), anyString(), any());

        function.run(request, queueMessage, context);

        assertEquals(txnWritten.get(), txnOnThread.get());
        assertNull(MDC.get(TRANSACTION_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }
}
