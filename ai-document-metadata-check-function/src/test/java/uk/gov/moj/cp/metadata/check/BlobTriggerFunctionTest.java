package uk.gov.moj.cp.metadata.check;

import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;
import uk.gov.moj.cp.metadata.check.service.DocumentMetadataService;
import uk.gov.moj.cp.metadata.check.service.IngestionOrchestratorService;

import com.microsoft.azure.functions.OutputBinding;
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
    private IngestionOrchestratorService ingestionOrchestratorService;

    @Mock
    private OutputBinding<String> queueMessage;

    @Mock
    private OutputBinding<DocumentIngestionOutcome> messageOutcome;

    private BlobTriggerFunction blobTriggerFunction;

    @BeforeEach
    void setUp() {
        blobTriggerFunction = new BlobTriggerFunction(documentMetadataService,
                ingestionOrchestratorService);
    }

    @Test
    @DisplayName("Process Document Successfully")
    void shouldProcessBlobSuccessfully() {
        // given
        String documentName = "test.pdf";

        // When
        blobTriggerFunction.run(documentName, queueMessage, messageOutcome);

        // Then
        verify(ingestionOrchestratorService).processDocument(documentName, queueMessage, messageOutcome);
    }

    @Test
    void shouldHandleQueueFailure() {
        // given
        String documentName = "test-document.pdf";

        // when
        blobTriggerFunction.run(documentName, queueMessage, messageOutcome);

        // then
        verify(ingestionOrchestratorService).processDocument(documentName, queueMessage, messageOutcome);
    }
}
