package uk.gov.moj.cp.metadata.check;

import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;
import uk.gov.moj.cp.metadata.check.service.QueueStorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlobTriggerFunctionTest {

    @Mock
    private DocumentMetadataService documentMetadataService;

    @Mock
    private QueueStorageService queueStorageService;

    @Mock
    private TableStorageService tableStorageService;

    @Mock
    private IngestionOrchestratorService ingestionOrchestratorService;

    private BlobTriggerFunction blobTriggerFunction;

    @BeforeEach
    void setUp() {
        blobTriggerFunction = new BlobTriggerFunction(documentMetadataService,
                queueStorageService,
                tableStorageService,
                ingestionOrchestratorService
        );
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessBlobSuccessfully() {
        // given
        String documentName = "test.pdf";

        // When
        blobTriggerFunction.run(documentName);

        // Then
        verify(ingestionOrchestratorService).processDocument(documentName);
    }

    @Test
    void shouldHandleQueueFailure() {
        // given
        String documentName = "test-document.pdf";

        // when
        blobTriggerFunction.run(documentName);

        // then
        verify(ingestionOrchestratorService).processDocument(documentName);
    }
}
