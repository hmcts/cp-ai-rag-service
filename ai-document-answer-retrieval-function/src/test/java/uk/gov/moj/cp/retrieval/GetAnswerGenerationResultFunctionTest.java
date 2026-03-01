package uk.gov.moj.cp.retrieval;

import static com.microsoft.azure.functions.HttpStatus.BAD_REQUEST;
import static java.util.UUID.fromString;
import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_PENDING;
import static uk.gov.moj.cp.ai.model.ChunkedEntry.builder;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.AnswerGenerationFunction.getInputChunksFilename;
import static uk.gov.moj.cp.retrieval.GetAnswerGenerationResultFunction.PARAM_WITH_CHUNKED_ENTRIES;

import uk.gov.hmcts.cp.openapi.model.UserQueryAnswerReturnedSuccessfullyAsynchronously;
import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetAnswerGenerationResultFunctionTest {

    @Mock
    private AnswerGenerationTableService tableStorageService;

    @Mock
    private BlobPersistenceService blobPersistenceInputChunksService;

    @InjectMocks
    private GetAnswerGenerationResultFunction function;

    @Mock
    private HttpRequestMessage<Optional<String>> request;

    @Mock
    private ExecutionContext context;

    @Mock
    private HttpResponseMessage.Builder responseBuilder;

    @Captor
    private ArgumentCaptor<String> bodyCaptor;


    @BeforeEach
    void setUp() {
        when(request.createResponseBuilder(any())).thenReturn(responseBuilder);
        when(responseBuilder.header("Content-Type", "application/json")).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
    }

    @Test
    void shouldReturnBadRequestWhenTransactionIdIsMissing() {
        function.run(request, null, context);

        verify(request).createResponseBuilder(BAD_REQUEST);
        verify(responseBuilder).body(contains("transactionId is required"));
    }

    @Test
    void shouldReturnBadRequestWhenTransactionIdIsNotValid() {
        function.run(request, "in-valid", context);

        verify(request).createResponseBuilder(BAD_REQUEST);
        verify(responseBuilder).body(contains("transactionId is required"));
    }

    @Test
    void shouldReturnGeneratedAnswer() throws EntityRetrievalException, JsonProcessingException, BlobParsingException {
        final String transactionId = randomUUID().toString();

        final GeneratedAnswer generatedAnswer = new GeneratedAnswer(transactionId,
                "user query 1", "query prompt 1", "transactionId.json",
                "LLM response", ANSWER_GENERATED.name(), null, OffsetDateTime.now(), 123L);

        when(request.getQueryParameters()).thenReturn(Map.of(PARAM_WITH_CHUNKED_ENTRIES, "true"));
        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);
        when(blobPersistenceInputChunksService.readBlob(eq(getInputChunksFilename(fromString(transactionId))), any())).thenReturn(new InputChunksPayload(List.of(builder()
                .id(randomUUID().toString()).chunk("").documentFileName("doc2").pageNumber(2).documentId(randomUUID().toString()).build())));

        function.run(request, transactionId, context);

        verify(responseBuilder).header("Content-Type", "application/json");
        verify(request).createResponseBuilder(HttpStatus.OK);
        verify(responseBuilder).body(bodyCaptor.capture());
        final UserQueryAnswerReturnedSuccessfullyAsynchronously asyncResponse = getObjectMapper().readValue(bodyCaptor.getValue(), UserQueryAnswerReturnedSuccessfullyAsynchronously.class);
        assertThat(asyncResponse.getTransactionId(), is(transactionId));
        assertThat(asyncResponse.getStatus(), is(ANSWER_GENERATED));
        assertThat(asyncResponse.getUserQuery(), is("user query 1"));
        assertThat(asyncResponse.getQueryPrompt(), is("query prompt 1"));
        assertThat(asyncResponse.getLlmResponse(), is("LLM response"));
        assertThat(asyncResponse.getResponseGenerationDuration(), is(123));
    }

    @Test
    void shouldReturnFailedAnswerGeneration() throws EntityRetrievalException, JsonProcessingException {
        final String transactionId = randomUUID().toString();

        final GeneratedAnswer generatedAnswer = mock(GeneratedAnswer.class);

        when(generatedAnswer.getTransactionId()).thenReturn(transactionId);
        when(generatedAnswer.getAnswerStatus()).thenReturn(ANSWER_GENERATION_FAILED.name());
        when(generatedAnswer.getUserQuery()).thenReturn("user query 1");
        when(generatedAnswer.getQueryPrompt()).thenReturn("query prompt 1");
        when(generatedAnswer.getResponseGenerationTime()).thenReturn(OffsetDateTime.now());
        when(generatedAnswer.getResponseGenerationDuration()).thenReturn(123L);
        when(request.getQueryParameters()).thenReturn(Map.of(PARAM_WITH_CHUNKED_ENTRIES, "false"));

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);

        function.run(request, transactionId, context);

        verify(responseBuilder).header("Content-Type", "application/json");
        verify(request).createResponseBuilder(HttpStatus.OK);
        verify(responseBuilder).body(bodyCaptor.capture());
        final UserQueryAnswerReturnedSuccessfullyAsynchronously asyncResponse = getObjectMapper().readValue(bodyCaptor.getValue(), UserQueryAnswerReturnedSuccessfullyAsynchronously.class);
        assertThat(asyncResponse.getTransactionId(), is(transactionId));
        assertThat(asyncResponse.getStatus(), is(ANSWER_GENERATION_FAILED));
    }

    @Test
    void shouldReturnPendingStatusWhenAnswerIsPending() throws EntityRetrievalException, JsonProcessingException {
        final String transactionId = randomUUID().toString();

        GeneratedAnswer generatedAnswer = mock(GeneratedAnswer.class);
        when(generatedAnswer.getTransactionId()).thenReturn(transactionId);
        when(generatedAnswer.getAnswerStatus()).thenReturn(ANSWER_GENERATION_PENDING.name());

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(generatedAnswer);

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.OK);
        verify(responseBuilder).body(bodyCaptor.capture());
        final UserQueryAnswerReturnedSuccessfullyAsynchronously asyncResponse = getObjectMapper().readValue(bodyCaptor.getValue(), UserQueryAnswerReturnedSuccessfullyAsynchronously.class);
        assertThat(asyncResponse.getTransactionId(), is(transactionId));
        assertThat(asyncResponse.getStatus(), is(ANSWER_GENERATION_PENDING));
        assertThat(asyncResponse.getResponseGenerationTime(), is(nullValue()));
        assertThat(asyncResponse.getResponseGenerationDuration(), is(nullValue()));
    }

    @Test
    void shouldReturnNotFoundWhenNoAnswerExists() throws EntityRetrievalException {
        final String transactionId = randomUUID().toString();

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenReturn(null);

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
    }

    @Test
    void shouldReturnInternalServerErrorOnException() throws EntityRetrievalException {
        final String transactionId = randomUUID().toString();

        when(tableStorageService.getGeneratedAnswer(transactionId)).thenThrow(new RuntimeException("DB failure"));

        function.run(request, transactionId, context);

        verify(request).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}