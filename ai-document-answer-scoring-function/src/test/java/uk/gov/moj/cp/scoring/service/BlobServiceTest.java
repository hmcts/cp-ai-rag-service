package uk.gov.moj.cp.scoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.scoring.exception.BlobParsingException;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobServiceTest {

    private BlobClientService blobClientServiceMock;
    private BlobClient blobClientMock;
    private BlobService blobService;

    @BeforeEach
    void setUp() {
        blobClientServiceMock = mock(BlobClientService.class);
        blobClientMock = mock(BlobClient.class);
        blobService = new BlobService(blobClientServiceMock);
    }

    @Test
    @DisplayName("Reads blob successfully when filename is valid")
    void readsBlobSuccessfullyWhenFilenameIsValid() throws Exception {
        final String filename = "validFile.json";
        final String blobContent = """
                {
                    "llmResponse": "response",
                    "userQuery": "query",
                    "queryPrompt": "prompt",
                    "chunkedEntries": [],
                    "transactionId": "12345"
                }
                """;

        when(blobClientServiceMock.getBlobClient(filename)).thenReturn(blobClientMock);
        when(blobClientMock.downloadContent()).thenReturn(BinaryData.fromString(blobContent));

        ScoringPayload actualResponse = blobService.readBlob(filename, ScoringPayload.class);

        verify(blobClientServiceMock).getBlobClient(filename);
        verify(blobClientMock).downloadContent();
        assertNotNull(actualResponse);
        assertEquals("response", actualResponse.llmResponse());
        assertEquals("query", actualResponse.userQuery());
        assertEquals("prompt", actualResponse.queryPrompt());
        assertEquals(0, actualResponse.chunkedEntries().size());
        assertEquals("12345", actualResponse.transactionId());
    }

    @Test
    @DisplayName("Throws BlobParsingException when blob content is invalid JSON")
    void throwsBlobParsingExceptionWhenBlobContentIsInvalidJson() throws Exception {
        String filename = "invalidFile.json";
        String blobContent = "invalid-json";

        when(blobClientServiceMock.getBlobClient(filename)).thenReturn(blobClientMock);
        when(blobClientMock.downloadContent()).thenReturn(BinaryData.fromString(blobContent));

        assertThrows(BlobParsingException.class, () -> blobService.readBlob(filename, ScoringPayload.class));
    }

    @Test
    @DisplayName("Throws BlobParsingException when filename is null")
    void throwsBlobParsingExceptionWhenFilenameIsNull() {
        assertThrows(BlobParsingException.class, () -> blobService.readBlob(null, ScoringPayload.class));
    }
}
