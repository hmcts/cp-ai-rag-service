package uk.gov.moj.cp.retrieval.service;

import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.service.BlobClientFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class BlobPersistenceServiceTest {

    @Mock
    private BlobClientFactory mockBlobClientFactory;

    private BlobPersistenceService blobPersistenceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        blobPersistenceService = new BlobPersistenceService(mockBlobClientFactory);
    }

    @Test
    void saveBlob_SavesBlobSuccessfully_WhenFilenameAndPayloadAreValid() {
        blobPersistenceService.saveBlob("file.txt", "payload");
        verify(mockBlobClientFactory).addBlob("file.txt", "payload");
    }
}
