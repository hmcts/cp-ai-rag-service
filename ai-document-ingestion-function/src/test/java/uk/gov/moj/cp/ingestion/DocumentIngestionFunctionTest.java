package uk.gov.moj.cp.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionFunctionTest {

    @Mock
    private DocumentIngestionOrchestrator documentIngestionOrchestrator;

    private DocumentIngestionFunction documentIngestionFunction;

    @BeforeEach
    void setUp() {
        documentIngestionFunction = new DocumentIngestionFunction(documentIngestionOrchestrator);
    }

    @Test
    @DisplayName("Process Queue Message Successfully")
    void shouldProcessQueueMessageSuccessfully() throws Exception {
        // given
        String queueMessage = """
                {
                  "documentId": "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                  "documentName": "Burglary-IDPC.pdf",
                  "metadata": {
                    "case_id": "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                    "document_id": "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                    "document_type": "MCC"
                  },
                  "blobUrl": "test-url",
                  "currentTimestamp": "2025-10-06T05:14:39.658828Z"
                }
                """;

        // when
        documentIngestionFunction.run(queueMessage);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(queueMessage);
    }

    @Test
    @DisplayName("Handle Empty Queue Message")
    void shouldHandleEmptyQueueMessage() throws Exception {
        // given
        String emptyMessage = "";

        // when
        documentIngestionFunction.run(emptyMessage);

        // then
        // Empty messages should return early without calling orchestrator
        verify(documentIngestionOrchestrator, never()).processQueueMessage(any());
    }

    @Test
    @DisplayName("Process Document with Different Metadata")
    void shouldProcessDocumentWithDifferentMetadata() throws Exception {
        // given
        String queueMessage = """
                {
                  "documentId": "123e4567-e89b-12d3-a456-426614174000",
                  "documentName": "Contract-Agreement.pdf",
                  "metadata": {
                    "case_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "document_id": "123e4567-e89b-12d3-a456-426614174000",
                    "document_type": "CONTRACT"
                  },
                  "blobUrl": "https://storage.blob.core.windows.net/container/Contract-Agreement.pdf",
                  "currentTimestamp": "2025-10-07T10:30:45.123456Z"
                }
                """;

        // when
        documentIngestionFunction.run(queueMessage);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(queueMessage);
    }

    @Test
    @DisplayName("Throw DocumentProcessingException When Orchestrator Fails")
    void shouldThrowDocumentProcessingExceptionWhenOrchestratorFails() throws Exception {
        // given
        String queueMessage = "{}";
        DocumentProcessingException orchestratorException = new DocumentProcessingException("Orchestrator failed");
        doThrow(orchestratorException).when(documentIngestionOrchestrator).processQueueMessage(any());

        // when & then
        DocumentProcessingException exception = assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage));
        assertEquals("Orchestrator failed", exception.getMessage());
    }

    @Test
    @DisplayName("Throw DocumentProcessingException When Unexpected Error Occurs")
    void shouldThrowDocumentProcessingExceptionWhenUnexpectedErrorOccurs() throws Exception {
        // given
        String queueMessage = "{}";
        RuntimeException unexpectedException = new RuntimeException("Unexpected error");
        doThrow(unexpectedException).when(documentIngestionOrchestrator).processQueueMessage(any());

        // when & then
        DocumentProcessingException exception = assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage));
        assertEquals("Function execution failed", exception.getMessage());
        assertEquals(RuntimeException.class, exception.getCause().getClass());
    }
}
