package uk.gov.moj.cp.azure.status.check;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;

import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DocumentStatusByReferenceFunctionLogContextTest {

    @SuppressWarnings("unchecked")
    private final HttpRequestMessage<Optional<String>> request = mock(HttpRequestMessage.class);
    private final HttpResponseMessage.Builder responseBuilder = mock(HttpResponseMessage.Builder.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final DocumentIngestionOutcomeTableService tableService = mock(DocumentIngestionOutcomeTableService.class);

    private DocumentStatusByReferenceFunction function;

    @BeforeEach
    void setUp() {
        when(context.getInvocationId()).thenReturn("inv-doc-status");
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));
        function = new DocumentStatusByReferenceFunction(tableService, null);
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("the path document reference is on the thread for the table lookup, and gone after run")
    void pathDocumentReferenceIsOnThreadForLookup() throws Exception {
        final String documentReference = randomUUID().toString();
        final AtomicReference<String> docOnThread = new AtomicReference<>();
        when(tableService.getDocumentById(null, documentReference)).thenAnswer(invocation -> {
            docOnThread.set(MDC.get(DOCUMENT_ID));
            return null; // 404 path; the lookup is what we are observing
        });

        function.run(request, documentReference, context);

        assertEquals(documentReference, docOnThread.get());
        assertNull(MDC.get(DOCUMENT_ID));
        assertNull(MDC.get(INVOCATION_ID));
    }
}
