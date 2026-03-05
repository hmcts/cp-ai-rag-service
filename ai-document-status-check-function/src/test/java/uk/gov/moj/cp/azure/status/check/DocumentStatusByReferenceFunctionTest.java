package uk.gov.moj.cp.azure.status.check;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import java.util.Optional;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DocumentStatusByReferenceFunctionTest {

    @Mock
    private DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

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
        function = new DocumentStatusByReferenceFunction(documentIngestionOutcomeTableService);

        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(response);
    }

    @Test
    void shouldReturnOkWhenDocumentExists() throws EntityRetrievalException {

        final DocumentIngestionOutcome outcome = mock(DocumentIngestionOutcome.class);
        final String documentId = randomUUID().toString();

        when(outcome.getDocumentId()).thenReturn(documentId);
        when(outcome.getDocumentName()).thenReturn("file.pdf");
        when(outcome.getStatus()).thenReturn("INGESTION_SUCCESS");
        when(outcome.getTimestamp()).thenReturn("2024-01-01T10:00:00Z");
        when(outcome.getReason()).thenReturn(null);
        when(documentIngestionOutcomeTableService.getDocumentById(documentId)).thenReturn(outcome);

        final HttpResponseMessage result = function.run(request, documentId, context);

        verify(documentIngestionOutcomeTableService).getDocumentById(documentId);
        verify(request).createResponseBuilder(HttpStatus.OK);
        assertEquals(response, result);
    }

    @Test
    void shouldReturnBadRequestWhenDocumentReferenceInvalid() {

        final String invalidDocumentReference = "";

        final HttpResponseMessage result = function.run(request, invalidDocumentReference, context);

        verify(request).createResponseBuilder(HttpStatus.BAD_REQUEST);
        assertEquals(response, result);
        verifyNoInteractions(documentIngestionOutcomeTableService);
    }

    @Test
    void shouldReturnNotFoundWhenNoDocumentInTheDBForTheDocumentReference() throws EntityRetrievalException {

        final String documentId = randomUUID().toString();
        when(documentIngestionOutcomeTableService.getDocumentById(documentId)).thenReturn(null);

        final HttpResponseMessage result = function.run(request, documentId, context);

        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
        assertEquals(response, result);
    }

    @Test
    void shouldReturnInternalServerErrorWhenServiceThrowsException() throws EntityRetrievalException {
        final String documentId = randomUUID().toString();
        when(documentIngestionOutcomeTableService.getDocumentById(documentId)).thenThrow(new RuntimeException("Service failure"));

        final HttpResponseMessage result = function.run(request, documentId, context);

        verify(request).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
        assertEquals(response, result);
    }
}