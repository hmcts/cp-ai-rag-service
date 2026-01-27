package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.Collections;
import java.util.List;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentChunkingServiceTest {

    @Mock
    private AnalyzeResult analyzeResult;

    private DocumentChunkingService documentChunkingService;
    private QueueIngestionMetadata metadata;

    @BeforeEach
    void setUp() {
        documentChunkingService = new DocumentChunkingService();
        metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000",
                "test-document.pdf",
                Collections.singletonMap("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/container/test-document.pdf",
                "2025-10-07T10:30:45.123456Z"
        );
    }

    @Test
    @DisplayName("Handle Null Analyze Result")
    void shouldHandleNullAnalyzeResult() {
        // when & then
        assertThrows(DocumentProcessingException.class,
                () -> documentChunkingService.chunkDocument(null, metadata));
    }

    @Test
    @DisplayName("Handle Null Queue Metadata")
    void shouldHandleNullQueueMetadata() {
        // when & then
        assertThrows(NullPointerException.class,
                () -> documentChunkingService.chunkDocument(analyzeResult, null));
    }

    @Test
    @DisplayName("Service Constructor Works")
    void shouldCreateService() {
        // when
        DocumentChunkingService service = new DocumentChunkingService();

        // then
        assertNotNull(service);
    }

    @Test
    @DisplayName("Chunk Document with Valid Inputs")
    void shouldChunkDocumentWithValidInputs() throws Exception {
        // when
        List<ChunkedEntry> chunks = documentChunkingService.chunkDocument(analyzeResult, metadata);

        // then
        assertNotNull(chunks);
    }

    @Test
    @DisplayName("Chunk Document with Different Document Types")
    void shouldChunkDocumentWithDifferentDocumentTypes() throws Exception {
        // given
        QueueIngestionMetadata mccMetadata = new QueueIngestionMetadata(
                "53ac8b90-c4c8-472c-a5ee-fe84ed96047b",
                "Burglary-IDPC.pdf",
                Collections.singletonMap("document_type", "MCC"),
                "https://storage.blob.core.windows.net/container/Burglary-IDPC.pdf",
                "2025-10-06T05:14:39.658828Z"
        );

        // when
        List<ChunkedEntry> chunks = documentChunkingService.chunkDocument(analyzeResult, mccMetadata);

        // then
        assertNotNull(chunks);
    }
}