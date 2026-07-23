package uk.gov.moj.cp.retrieval;

import static java.util.UUID.randomUUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;

import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Enforcement wiring for the async status/poll endpoint: a rejected identity is a 401, the lookup
 * is scoped by the resolved identity, and a transactionId owned by another client resolves to 404.
 */
@ExtendWith(MockitoExtension.class)
class GetAnswerGenerationResultFunctionClientIdentityTest {

    private static final String CLIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CLIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Mock
    private AnswerGenerationTableService tableService;
    @Mock
    private BlobPersistenceService blobPersistenceInputChunksService;
    @Mock
    private ClientIdentityResolver clientIdentityResolver;
    @Mock
    private HttpRequestMessage<Optional<String>> request;
    @Mock
    private HttpResponseMessage.Builder responseBuilder;
    @Mock
    private HttpResponseMessage response;
    @Mock
    private ExecutionContext context;

    private GetAnswerGenerationResultFunction function;

    @BeforeEach
    void setUp() {
        function = new GetAnswerGenerationResultFunction(tableService, blobPersistenceInputChunksService, clientIdentityResolver);
        lenient().when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        lenient().when(responseBuilder.header(any(), any())).thenReturn(responseBuilder);
        lenient().when(responseBuilder.body(any())).thenReturn(responseBuilder);
        lenient().when(responseBuilder.build()).thenReturn(response);
    }

    @Test
    @DisplayName("returns 401 when the client identity is rejected")
    void shouldReturnUnauthorised_whenIdentityRejected() {
        when(clientIdentityResolver.resolve(request)).thenThrow(new ClientIdentityException("missing or invalid client identity"));

        function.run(request, randomUUID().toString(), context);

        verify(request).createResponseBuilder(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("scopes the answer lookup by the resolved client identity")
    void shouldScopeLookupByResolvedClientId() throws Exception {
        final String transactionId = randomUUID().toString();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_A));

        function.run(request, transactionId, context);

        verify(tableService).getGeneratedAnswer(CLIENT_A, transactionId);
    }

    @Test
    @DisplayName("returns 404 for a transactionId owned by another client — never the owner's answer")
    void shouldReturnNotFound_whenTransactionOwnedByAnotherClient() throws Exception {
        final String transactionId = randomUUID().toString();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_B));
        lenient().when(request.getQueryParameters()).thenReturn(Map.of());
        // Client A owns the answer; an unscoped (legacy) lookup would find it. Client B's scoped lookup must not.
        lenient().when(tableService.getGeneratedAnswer(null, transactionId)).thenReturn(ownersAnswer(transactionId));

        function.run(request, transactionId, context);

        verify(tableService).getGeneratedAnswer(CLIENT_B, transactionId);
        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("flag off (default resolver) keeps the legacy null-scoped lookup")
    void shouldUseLegacyLookup_whenEnforcementOff() throws Exception {
        final String transactionId = randomUUID().toString();
        final GetAnswerGenerationResultFunction defaultFunction =
                new GetAnswerGenerationResultFunction(tableService, blobPersistenceInputChunksService, null);

        defaultFunction.run(request, transactionId, context);

        verify(tableService).getGeneratedAnswer(null, transactionId);
    }

    private GeneratedAnswer ownersAnswer(final String transactionId) {
        return new GeneratedAnswer(transactionId, "user query", "query prompt", null,
                "LLM response", ANSWER_GENERATED.name(),
                null, OffsetDateTime.now(), 123L);
    }
}
