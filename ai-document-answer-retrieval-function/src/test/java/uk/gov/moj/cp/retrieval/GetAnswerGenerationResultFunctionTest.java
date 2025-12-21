package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetAnswerGenerationResultFunctionTest {

    @Mock
    private ResponseGenerationService responseGenerationService;

    @Mock
    private AnswerGenerationTableStorageService tableStorageService;

    @InjectMocks
    private GetAnswerGenerationResultFunction function;

    @Mock
    private HttpRequestMessage<Optional<String>> request;

    @Mock
    private ExecutionContext context;

    @Mock
    private HttpResponseMessage.Builder responseBuilder;

    @BeforeEach
    void setUp() {
        when(request.createResponseBuilder(any())).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), any())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
    }

    @Test
    void shouldReturnBadRequestWhenTransactionIdIsMissing() {
        function.run(request, "", context);

        verify(request).createResponseBuilder(BAD_REQUEST);
        verify(responseBuilder).body(contains("transactionId is required"));
    }

    @Test
    void shouldReturnGeneratedAnswer() throws EntityRetrievalException {
        final String transactionId = UUID.randomUUID().toString();

        final GeneratedAnswer generatedAnswer = mock(GeneratedAnswer.class);

        when(generatedAnswer.getTransactionId()).thenReturn(transactionId);
        when(generatedAnswer.getAnswerStatus()).thenReturn(ANSWER_GENERATED.name());
        when(generatedAnswer.getUserQuery()).thenReturn("user query 1");
        when(generatedAnswer.getQueryPrompt()).thenReturn("query prompt 1");
        when(generatedAnswer.getChunkedEntries()).thenReturn(List.of());
        when(generatedAnswer.getResponseGenerationTime()).thenReturn(OffsetDateTime.now());
        when(generatedAnswer.getResponseGenerationDuration()).thenReturn(123L);

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);
        when(responseGenerationService.generateResponse(any(), any(), any())).thenReturn("generated response");

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.OK);
        verify(responseGenerationService).generateResponse(eq("user query 1"), any(), eq("query prompt 1"));
    }

    @Test
    void shouldReturnFailedAnswerGeneration() throws EntityRetrievalException {
        final String transactionId = UUID.randomUUID().toString();

        final GeneratedAnswer generatedAnswer = mock(GeneratedAnswer.class);

        when(generatedAnswer.getTransactionId()).thenReturn(transactionId);
        when(generatedAnswer.getAnswerStatus()).thenReturn(ANSWER_GENERATION_FAILED.name());
        when(generatedAnswer.getUserQuery()).thenReturn("user query 1");
        when(generatedAnswer.getQueryPrompt()).thenReturn("query prompt 1");
        when(generatedAnswer.getChunkedEntries()).thenReturn(List.of());
        when(generatedAnswer.getResponseGenerationTime()).thenReturn(OffsetDateTime.now());
        when(generatedAnswer.getResponseGenerationDuration()).thenReturn(123L);

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.OK);
        verifyNoInteractions(responseGenerationService);
    }

    @Test
    void shouldReturnPendingStatusWhenAnswerIsPending() throws EntityRetrievalException {
        final String transactionId = UUID.randomUUID().toString();

        GeneratedAnswer generatedAnswer = mock(GeneratedAnswer.class);
        when(generatedAnswer.getTransactionId()).thenReturn(transactionId);
        when(generatedAnswer.getAnswerStatus()).thenReturn(ANSWER_GENERATION_PENDING.name());

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.OK);
        verify(responseGenerationService, never()).generateResponse(any(), any(), any());
    }

    @Test
    void shouldReturnNotFoundWhenNoAnswerExists() throws EntityRetrievalException {
        final String transactionId = UUID.randomUUID().toString();

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(null);

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnInternalServerErrorOnException() throws EntityRetrievalException {
        final String transactionId = UUID.randomUUID().toString();

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenThrow(new RuntimeException("DB failure"));

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}