package uk.gov.moj.cp.metadata.check;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.client.identity.ClientContext;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityException;
import uk.gov.moj.cp.ai.client.identity.ClientIdentityResolver;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.service.DocumentUploadService;
import uk.gov.moj.cp.metadata.check.utils.DocumentBlobNameResolver;

import java.util.List;

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
 * Enforcement wiring for the document-upload endpoint: when enforcement is on the resolved client
 * identity gates the request (401 on a missing/invalid header) and is threaded into the dedup
 * lookup and the SAS blob name; only the header-derived identity is ever used.
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private BlobClientService blobClientService;
    @Mock
    private DocumentUploadService documentUploadService;
    @Mock
    private DocumentBlobNameResolver documentBlobNameResolver;
    @Mock
    private ClientIdentityResolver clientIdentityResolver;
    @Mock
    private HttpRequestMessage<DocumentUploadRequest> request;
    @Mock
    private HttpResponseMessage.Builder responseBuilder;
    @Mock
    private HttpResponseMessage response;
    @Mock
    private ExecutionContext context;

    private DocumentUploadFunction function;

    @BeforeEach
    void setUp() {
        function = new DocumentUploadFunction(blobClientService, documentUploadService, documentBlobNameResolver, clientIdentityResolver);
    }

    @Test
    @DisplayName("returns 401 and performs no downstream work when the client identity is rejected")
    void shouldReturnUnauthorised_whenIdentityRejected() throws Exception {
        when(clientIdentityResolver.resolve(request)).thenThrow(new ClientIdentityException("missing or invalid client identity"));
        mockResponseBuilder();

        function.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.UNAUTHORIZED);
        verify(blobClientService, never()).getSasUrl(any(), anyInt());
        verify(documentUploadService, never()).addDocumentAwaitingUpload(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("threads the resolved client id into the dedup lookup and the SAS blob name")
    void shouldThreadClientId_intoDedupAndBlobName() {
        final DocumentUploadRequest body = validRequest();
        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        when(request.getBody()).thenReturn(body);
        mockResponseBuilder();

        function.run(request, context);

        verify(documentUploadService).isDocumentAlreadyProcessed(CLIENT_ID, body.getDocumentId());
        verify(documentBlobNameResolver).getBlobName(eq(CLIENT_ID), eq(body.getDocumentId()), any());
    }

    @Test
    @DisplayName("uses only the header-derived identity — a spoofed clientId in the request body has no effect")
    void shouldUseHeaderIdentityOnly_whenBodyCarriesSpoofedClientId() {
        // The body's metadataFilter carries a different clientId-shaped value; the dedup lookup must
        // still be scoped by the header-resolved identity, never the body value.
        final MetadataFilter spoof = new MetadataFilter();
        spoof.setKey("clientId");
        spoof.setValue("99999999-9999-9999-9999-999999999999");
        final DocumentUploadRequest body = new DocumentUploadRequest(randomUUID().toString(), "test.pdf", List.of(spoof));

        when(clientIdentityResolver.resolve(request)).thenReturn(ClientContext.of(CLIENT_ID));
        when(request.getBody()).thenReturn(body);
        mockResponseBuilder();

        function.run(request, context);

        verify(documentUploadService).isDocumentAlreadyProcessed(CLIENT_ID, body.getDocumentId());
        verify(documentUploadService, never()).isDocumentAlreadyProcessed(eq("99999999-9999-9999-9999-999999999999"), any());
    }

    @Test
    @DisplayName("flag off (default resolver) is unchanged — no 401 and the lookup stays legacy-keyed")
    void shouldBehaveAsBefore_whenEnforcementOff() {
        // A default, environment-built resolver with the flag unset resolves to an unenforced context,
        // so the legacy null-scoped lookup and success path are preserved byte-for-byte.
        final DocumentUploadFunction defaultFunction =
                new DocumentUploadFunction(blobClientService, documentUploadService, documentBlobNameResolver, null);
        final DocumentUploadRequest body = validRequest();
        when(request.getBody()).thenReturn(body);
        when(documentBlobNameResolver.getBlobName(any(), any())).thenReturn("blob.pdf");
        when(blobClientService.getSasUrl(any(), anyInt())).thenReturn("http://sas");
        mockResponseBuilder();

        final HttpResponseMessage result = defaultFunction.run(request, context);

        assertEquals(response, result);
        verify(documentUploadService).isDocumentAlreadyProcessed(null, body.getDocumentId());
    }

    private DocumentUploadRequest validRequest() {
        final MetadataFilter metadataFilter = new MetadataFilter();
        metadataFilter.setKey("document_id");
        metadataFilter.setValue(randomUUID().toString());
        return new DocumentUploadRequest(randomUUID().toString(), "test.pdf", List.of(metadataFilter));
    }

    private void mockResponseBuilder() {
        when(request.createResponseBuilder(any(HttpStatus.class))).thenReturn(responseBuilder);
        when(responseBuilder.header(any(), any())).thenReturn(responseBuilder);
        when(responseBuilder.body(any())).thenReturn(responseBuilder);
        when(responseBuilder.build()).thenReturn(response);
    }
}
