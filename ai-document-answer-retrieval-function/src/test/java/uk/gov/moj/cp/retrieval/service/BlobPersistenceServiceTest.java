package uk.gov.moj.cp.retrieval.service;

import static java.util.UUID.randomUUID;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.service.BlobClientService;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class BlobPersistenceServiceTest {

    private static final String FILE_NAME = "file.txt";

    @Mock
    private BlobClientService mockBlobClientService;
    @Mock
    private BlobClient mockBlobClient;
    @Mock
    private BinaryData mockBinaryContent;

    private BlobPersistenceService blobPersistenceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        blobPersistenceService = new BlobPersistenceService(mockBlobClientService);
    }

    @Test
    void saveBlob_SavesBlobSuccessfully_WhenFilenameAndPayloadAreValid() {
        blobPersistenceService.saveBlob("file.txt", "payload");
        verify(mockBlobClientService).addBlob("file.txt", "payload");
    }

    @Test
    void readBlob_ReadBlobSuccessfully_WhenFilenameAndPayloadAreValid() throws BlobParsingException, JsonProcessingException {
        final InputChunksPayload chunksPayload = new InputChunksPayload(List.of(ChunkedEntry.builder().id(randomUUID().toString()).build()));
        final String chunksPayloadAsString = getObjectMapper().writeValueAsString(chunksPayload);

        when(mockBlobClientService.getBlobClient(FILE_NAME)).thenReturn(mockBlobClient);
        when(mockBlobClient.downloadContent()).thenReturn(mockBinaryContent);
        when(mockBinaryContent.toBytes()).thenReturn(chunksPayloadAsString.getBytes(StandardCharsets.UTF_8));

        final InputChunksPayload inputChunksPayload = blobPersistenceService.readBlob(FILE_NAME, InputChunksPayload.class);
        assertThat(inputChunksPayload, is(chunksPayload));
    }

    @Test
    void readBlob_ReadBlobFail_WhenFilenameIsNull() throws BlobParsingException {

        final BlobParsingException ex = assertThrows(
                BlobParsingException.class,
                () -> blobPersistenceService.readBlob(null, InputChunksPayload.class)
        );

        assertThat(ex.getMessage(), is("Unable to process blob as file name is null or empty"));
    }
}
