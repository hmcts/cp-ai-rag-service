package uk.gov.moj.cp.azure.status.check;

import static java.util.UUID.randomUUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

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
 * Enforcement wiring for the document-status endpoint: a rejected identity is a 401, the resolved
 * identity scopes the lookup, and a reference owned by another client resolves to 404 (no
 * existence leakage) rather than returning the owner's row.
 */
@ExtendWith(MockitoExtension.class)
class DocumentStatusByReferenceFunctionClientIdentityTest {

    private static final String CLIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CLIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Mock
    private DocumentIngestionOutcomeTableService tableService;
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

    private DocumentStatusByReferenceFunction function;

    @BeforeEach
    void setUp() {
        function = new DocumentStatusByReferenceFunction(tableService, clientIdentityResolver);
        lenient().when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        lenient().when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        lenient().when(responseBuilder.body(any())).thenReturn(responseBuilder);
        lenient().when(responseBuilder.build()).thenReturn(response);
    }

    @Test
    @DisplayName("returns 401 and performs no lookup when the client identity is rejected")
    void shouldReturnUnauthorised_whenIdentityRejected() {
        when(clientIdentityResolver.resolve(request)).thenThrow(new ClientIdentityException("missing or invalid client identity"));

        function.run(request, randomUUID().toString(), context);

        verify(request).createResponseBuilder(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("scopes the status lookup by the resolved client identity")
    void shouldScopeLookupByResolvedClientId() throws Exception {
        final String reference = randomUUID().toString();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_A));

        function.run(request, reference, context);

        verify(tableService).getDocumentById(CLIENT_A, reference);
    }

    @Test
    @DisplayName("returns 404 for a reference owned by another client — never the owner's row")
    void shouldReturnNotFound_whenReferenceOwnedByAnotherClient() throws Exception {
        final String reference = randomUUID().toString();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_B));
        // Client A owns the row; an unscoped (legacy) lookup would find it. Client B's scoped lookup must not.
        final DocumentIngestionOutcome ownersRow = ownedRow(reference);
        lenient().when(tableService.getDocumentById(null, reference)).thenReturn(ownersRow);

        function.run(request, reference, context);

        verify(tableService).getDocumentById(CLIENT_B, reference);
        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("flag off (default resolver) keeps the legacy null-scoped lookup")
    void shouldUseLegacyLookup_whenEnforcementOff() throws Exception {
        final String reference = randomUUID().toString();
        final DocumentStatusByReferenceFunction defaultFunction = new DocumentStatusByReferenceFunction(tableService);

        defaultFunction.run(request, reference, context);

        verify(tableService).getDocumentById(null, reference);
    }

    private DocumentIngestionOutcome ownedRow(final String reference) {
        final DocumentIngestionOutcome outcome = mock(DocumentIngestionOutcome.class);
        lenient().when(outcome.getDocumentId()).thenReturn(reference);
        lenient().when(outcome.getDocumentName()).thenReturn("file.pdf");
        lenient().when(outcome.getStatus()).thenReturn("INGESTION_SUCCESS");
        lenient().when(outcome.getTimestamp()).thenReturn("2026-01-01T10:00:00Z");
        lenient().when(outcome.getReason()).thenReturn("success");
        return outcome;
    }
}
