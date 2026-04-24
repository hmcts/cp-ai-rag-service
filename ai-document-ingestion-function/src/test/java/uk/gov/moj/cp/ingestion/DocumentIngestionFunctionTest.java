package uk.gov.moj.cp.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.util.ObjectMapperFactory;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString(),
                false
        );

        final String queueMessage = new ObjectMapper().writeValueAsString(metadata);

        // when
        documentIngestionFunction.run(queueMessage, 1);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata);
    }

    @Test
    @DisplayName("Handle Empty Queue Message")
    void shouldHandleEmptyQueueMessage() throws Exception {
        // given
        final String emptyMessage = "";

        // when
        documentIngestionFunction.run(emptyMessage, 1);

        // then
        // Empty messages should return early without calling orchestrator
        verify(documentIngestionOrchestrator, never()).processQueueMessage(any());
    }

    @Test
    @DisplayName("Process Document with Different Metadata")
    void shouldProcessDocumentWithDifferentMetadata() throws Exception {
        // given
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Map.of("case_id", "b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9",
                        "document_type", "MCC"),
                "https://storage.blob.core.windows.net/documents/Burglary-IDPC.pdf",
                Instant.now().toString(),
                false
        );

        final String queueMessage = new ObjectMapper().writeValueAsString(metadata);

        // when
        documentIngestionFunction.run(queueMessage, 1);

        // then
        verify(documentIngestionOrchestrator).processQueueMessage(metadata);
    }

    @Test
    @DisplayName("Throw DocumentProcessingException When Orchestrator Fails")
    void shouldThrowDocumentProcessingExceptionWhenOrchestratorFails() throws Exception {
        // given
        final String queueMessage = "{}";
        final DocumentProcessingException orchestratorException = new DocumentProcessingException("Orchestrator failed");
        doThrow(orchestratorException).when(documentIngestionOrchestrator).processQueueMessage(any());

        // when & then
        final DocumentProcessingException exception = assertThrows(DocumentProcessingException.class,
                () -> documentIngestionFunction.run(queueMessage, 1));
        assertEquals("Error processing queueMessage", exception.getMessage());
    }

    @Test
    @DisplayName("Update DocumentIngestion failed When Orchestrator Fails and all retry attempts exhausted")
    void shouldUpdatedDocumentIngestionFailedWhenThrowsDocumentProcessingExceptionAndRetryAttemptsExhausted() throws Exception {
        // given
        final String queueMessage = "{}";
        final DocumentProcessingException orchestratorException = new DocumentProcessingException("Orchestrator failed");
        doThrow(orchestratorException).when(documentIngestionOrchestrator).processQueueMessage(any());

        // when & then
        documentIngestionFunction.run(queueMessage, 3);

        verify(documentIngestionOrchestrator).processQueueMessageFailed(queueMessage);
    }

    @Test
    @DisplayName("Throw JsonProcessingException and log error when Fails")
    void shouldLogAndNotThrowWhenJsonDeserializationFails() throws Exception {
        final String queueMessage = "{}";
        try (MockedStatic<ObjectMapperFactory> mocked = mockStatic(ObjectMapperFactory.class)) {
            final ObjectMapper objectMapper = mock(ObjectMapper.class);
            mocked.when(ObjectMapperFactory::getObjectMapper).thenReturn(objectMapper);
            when(objectMapper.readValue(queueMessage, QueueIngestionMetadata.class))
                    .thenThrow(new JsonProcessingException("Invalid JSON") {
                    });

            assertDoesNotThrow(() -> documentIngestionFunction.run(queueMessage, 1L));

            verifyNoInteractions(documentIngestionOrchestrator);
        }
    }

}
