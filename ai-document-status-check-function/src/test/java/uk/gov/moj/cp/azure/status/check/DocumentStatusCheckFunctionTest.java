package uk.gov.moj.cp.azure.status.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.azure.status.check.model.DocumentStatusRetrievedResponse;

import java.util.HashMap;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentStatusCheckFunctionTest {

    @Mock
    private TableStorageService tableStorageService;

    @Mock
    private ExecutionContext mockContext;

    @Mock
    private HttpRequestMessage<Optional<String>> mockRequest;

    @Mock
    private HttpResponseMessage.Builder mockResponseBuilder;

    private DocumentStatusCheckFunction function;

    @BeforeEach
    void setUp() {
        function = new DocumentStatusCheckFunction(tableStorageService);
    }

    @Test
    @DisplayName("returnsOkResponseWhenDocumentIsFound")
    void returnsOkResponseWhenDocumentIsFound() {
        String documentName = "test-document";
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome("123", documentName, "COMPLETED", "No issues", "2023-10-01T10:00:00Z");
        when(tableStorageService.getFirstDocumentMatching(documentName)).thenReturn(outcome);

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("document-name", documentName);
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);

        when(mockRequest.createResponseBuilder(HttpStatus.OK)).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.header("Content-Type", "application/json")).thenReturn(mockResponseBuilder);
        when(mockResponseBuilder.body(any())).thenReturn(mockResponseBuilder);

        final HttpResponseMessage mockResponse = mock(HttpResponseMessage.class);
        when(mockResponseBuilder.build()).thenReturn(mockResponse);

        function.run(mockRequest, mockContext);

        ArgumentCaptor<DocumentStatusRetrievedResponse> bodyCaptor = ArgumentCaptor.forClass(DocumentStatusRetrievedResponse.class);

        verify(mockResponseBuilder).header("Content-Type", "application/json");
        verify(mockResponseBuilder).build();

        verify(mockResponseBuilder).body(bodyCaptor.capture());
        DocumentStatusRetrievedResponse capturedBody = bodyCaptor.getValue();
        assertEquals("123", capturedBody.documentId());
        assertEquals(documentName, capturedBody.documentName());
        assertEquals("No issues", capturedBody.reason());
        assertEquals("COMPLETED", capturedBody.status());
        assertEquals("2023-10-01T10:00:00Z", capturedBody.lastUpdated());

    }

    @Test
    @DisplayName("returnsNotFoundResponseWhenDocumentIsNotFound")
    void returnsNotFoundResponseWhenDocumentIsNotFound() {
        String documentName = "non-existent-document";
        when(tableStorageService.getFirstDocumentMatching(documentName)).thenReturn(null);

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
    @DisplayName("throwsExceptionWhenDocumentNameIsMissing")
    void throwsExceptionWhenDocumentNameIsMissing() {
        Map<String, String> queryParams = new HashMap<>();
        when(mockRequest.getQueryParameters()).thenReturn(queryParams);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            function.run(mockRequest, mockContext);
        });

        assertEquals("Query parameter `document-name` is required and cannot be null or empty.", exception.getMessage());
    }
}
