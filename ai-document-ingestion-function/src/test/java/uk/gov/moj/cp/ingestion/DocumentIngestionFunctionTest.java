package uk.gov.moj.cp.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString()
        );

        String queueMessage = new ObjectMapper().writeValueAsString(metadata);

        // when
        documentIngestionFunction.run(queueMessage);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata);
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
        QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString()
        );

        String queueMessage = new ObjectMapper().writeValueAsString(metadata);

        // when
        documentIngestionFunction.run(queueMessage);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata);
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
        assertEquals("Error processing queueMessage", exception.getMessage());
    }
}
