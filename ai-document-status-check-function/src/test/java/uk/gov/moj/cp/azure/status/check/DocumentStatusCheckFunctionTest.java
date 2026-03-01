package uk.gov.moj.cp.azure.status.check;

import static java.time.OffsetDateTime.now;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatus.INGESTION_SUCCESS;

import uk.gov.hmcts.cp.openapi.model.DocumentIngestionStatusReturnedSuccessfully;
import uk.gov.hmcts.cp.openapi.model.DocumentStatusNotAvailable;
import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentStatusCheckFunctionTest {

    @Mock
    private DocumentIngestionOutcomeTableService documentIngestionOutcomeTableService;

    @Mock
    private ExecutionContext mockContext;

    @Mock
    private HttpRequestMessage<Optional<String>> mockRequest;

    @Mock
    private HttpResponseMessage.Builder mockResponseBuilder;

    private DocumentStatusCheckFunction function;

    @BeforeEach
    void setUp() {
        function = new DocumentStatusCheckFunction(documentIngestionOutcomeTableService);
    }

    @Test
    void returnsOkResponseWhenDocumentIsFound() throws EntityRetrievalException {
        String documentName = "test-document";
        final String statusTimestamp = now().toString();
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome("123", documentName, INGESTION_SUCCESS.getValue(), "No issues", statusTimestamp);
        when(documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName)).thenReturn(outcome);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("document-name", documentName);
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);

        when(mockRequest.createResponseBuilder(HttpStatus.OK)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);

        final HttpResponseMessage mockResponse = mock(HttpResponseMessage.class);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);

        function.run(mockRequest, mockContext);

        final ArgumentCaptor<DocumentIngestionStatusReturnedSuccessfully> bodyCaptor = ArgumentCaptor.forClass(DocumentIngestionStatusReturnedSuccessfully.class);

        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).build();

        verify(mockResponseBuilder).body(bodyCaptor.capture());
        DocumentIngestionStatusReturnedSuccessfully capturedBody = bodyCaptor.getValue();
        assertEquals("123", capturedBody.getDocumentId());
        assertEquals(documentName, capturedBody.getDocumentName());
        assertEquals("No issues", capturedBody.getReason());
        assertEquals(INGESTION_SUCCESS, capturedBody.getStatus());
        assertEquals(statusTimestamp, capturedBody.getLastUpdated().toString());

    }

    @Test
    void returnsNotFoundResponseWhenDocumentIsNotFound() throws EntityRetrievalException {
        String documentName = "non-existent-document";
        when(documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName)).thenReturn(null);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("document-name", documentName);
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);
        HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
        when(mockRequest.createResponseBuilder(HttpStatus.NOT_FOUND)).thenReturn(builder);
        when(builder.body(any())).thenReturn(builder);
        when(builder.header(anyString(), anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(HttpResponseMessage.class));

        HttpResponseMessage response = function.run(mockRequest, mockContext);

        verify(builder).body(any());
        verify(builder).header("Content-Type", "application/json");
        verify(builder).build();
    }

    @Test
    void returnsServerErrorResponseWhenErroringWhilstRetrievingEntity() throws EntityRetrievalException {
        String documentName = "random-document";
        when(documentIngestionOutcomeTableService.getFirstDocumentMatching(documentName)).thenThrow(EntityRetrievalException.class);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("document-name", documentName);
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);
        when(mockRequest.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header(anyString(), anyString())).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.build()).thenReturn(mock(HttpResponseMessage.class));

        HttpResponseMessage response = function.run(mockRequest, mockContext);

        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).build();

        final ArgumentCaptor<DocumentStatusNotAvailable> bodyCaptor = ArgumentCaptor.forClass(DocumentStatusNotAvailable.class);
        verify(mockResponseBuilder).body(bodyCaptor.capture());
        DocumentStatusNotAvailable capturedBody = bodyCaptor.getValue();
        assertEquals(documentName, capturedBody.getDocumentName());
    }

    @Test
    void throwsExceptionWhenDocumentNameIsMissing() {
        final Map<String, String> queryParams = new HashMap<>();
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);
        when(mockRequest.createResponseBuilder(HttpStatus.BAD_REQUEST)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);

        final HttpResponseMessage response = function.run(mockRequest, mockContext);

        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).build();

        final ArgumentCaptor<DocumentStatusNotAvailable> bodyCaptor = ArgumentCaptor.forClass(DocumentStatusNotAvailable.class);
        verify(mockResponseBuilder).body(bodyCaptor.capture());
        DocumentStatusNotAvailable capturedBody = bodyCaptor.getValue();
        assertEquals("N/A", capturedBody.getDocumentName());
    }
}
