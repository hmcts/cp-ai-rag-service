package uk.gov.moj.cp.metadata.check;

import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DocumentUploadFunctionTest {

    @Mock
    private BlobClientService blobClientService;

    @Mock
    private DocumentUploadService documentUploadService;

    @Mock
    private HttpRequestMessage<DocumentUploadRequest> request;

    @Mock
    private HttpResponseMessage.Builder responseBuilder;

    @Mock
    private HttpResponseMessage response;

    @Mock
    private ExecutionContext context;

    private DocumentUploadFunction function;
    @Captor
    private ArgumentCaptor<String> blobNameCaptor;

    @BeforeEach
    void setup() {
        function = new DocumentUploadFunction(blobClientService, documentUploadService);
    }

    @Test
    void shouldReturn400_whenValidationFails() {
        final DocumentUploadRequest invalidRequest = new DocumentUploadRequest();
        when(request.getBody()).thenReturn(invalidRequest);
        mockResponseBuilder(HttpStatus.BAD_REQUEST);

        final HttpResponseMessage result = function.run(request, context);

        assertThat(response, is(result));
        verifyNoInteractions(blobClientService);
    }

    @Test
    void shouldReturn400_whenDocumentAlreadyProcessed() {

        final DocumentUploadRequest body = validRequest();

        when(request.getBody()).thenReturn(body);
        when(documentUploadService.isDocumentAlreadyProcessed(body.getDocumentId())).thenReturn(true);
        mockResponseBuilder(HttpStatus.BAD_REQUEST);

        final HttpResponseMessage result = function.run(request, context);

        assertThat(response, is(result));
        verify(blobClientService, never()).getSasUrl(any(), anyInt());
        verify(documentUploadService, never()).recordUploadInitiated(any(), any());
    }

    @Test
    void shouldReturn200_whenRequestIsValid() {
        final DocumentUploadRequest body = validRequest();

        when(request.getBody()).thenReturn(body);
        when(documentUploadService.isDocumentAlreadyProcessed(body.getDocumentId())).thenReturn(false);
        when(blobClientService.getSasUrl(any(String.class), anyInt())).thenReturn("http://sas-url");
        mockResponseBuilder(HttpStatus.OK);

        final HttpResponseMessage result = function.run(request, context);

        assertThat(response, is(result));
        verify(documentUploadService).recordUploadInitiated(body.getDocumentName(), body.getDocumentId());
    }

    @Test
    void shouldCreateValidBlobName() {
        final DocumentUploadRequest body = validRequest();

        when(request.getBody()).thenReturn(body);
        when(documentUploadService.isDocumentAlreadyProcessed(body.getDocumentId())).thenReturn(false);
        when(blobClientService.getSasUrl(blobNameCaptor.capture(), anyInt())).thenReturn("http://sas-url");
        mockResponseBuilder(HttpStatus.OK);

        final HttpResponseMessage result = function.run(request, context);

        assertThat(response, is(result));
        final String blobNameCaptorValue = blobNameCaptor.getValue();
        assertThat(blobNameCaptorValue, is(getBlobName(body.getDocumentId())));
    }

    @Test
    void shouldReturn500_whenExceptionOccurs() {
        final DocumentUploadRequest body = validRequest();

        when(request.getBody()).thenReturn(body);
        when(documentUploadService.isDocumentAlreadyProcessed(any())).thenThrow(new RuntimeException("boom"));
        mockResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);

        HttpResponseMessage result = function.run(request, context);

        assertThat(response, is(result));
    }

    private DocumentUploadRequest validRequest() {
        final MetadataFilter metadataFilter = new MetadataFilter();
        metadataFilter.setKey("document_id");
        metadataFilter.setValue(randomUUID().toString());
        return new DocumentUploadRequest(
                randomUUID().toString(),
                "test.pdf",
                List.of(metadataFilter)
        );
    }

    private void mockResponseBuilder(HttpStatus expectedStatus) {
        when(request.createResponseBuilder(expectedStatus)).thenReturn(responseBuilder);
        when(responseBuilder.header(anyString(), anyString())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(response);
    }

    private String getBlobName(final String documentId) {
        final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        final String today = LocalDateTime.now().format(dateTimeFormatter);
        return documentId + "_" + today + ".pdf";
    }

}