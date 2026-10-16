package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.Collections;
import java.util.List;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.DocumentLine;
import com.azure.ai.documentintelligence.models.DocumentPage;
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

    @Test
    @DisplayName("AC-1 (DD-43810): toggle off space-joins lines even when line geometry is present")
    void shouldSpaceJoinLinesWhenToggleOff() throws Exception {
        // given: a two-column page where every line carries a usable polygon
        final List<DocumentLine> lines = List.of(
                lineWithPolygon("1. DRIVE A MOTOR VEHICLE WITH THE", 0.5, 1.00, 3.0, 1.12),
                lineWithPolygon("FINE 120.00", 4.0, 1.00, 5.5, 1.12),
                lineWithPolygon("PROPORTION OF SPECIFIED CONTROLLED", 0.5, 1.20, 3.0, 1.32),
                lineWithPolygon("DISQUALIFIED FROM DRIVING -", 4.0, 1.20, 5.5, 1.32));
        final DocumentPage page = mock(DocumentPage.class);
        when(page.getLines()).thenReturn(lines);
        when(analyzeResult.getPages()).thenReturn(List.of(page));

        final String expected = "1. DRIVE A MOTOR VEHICLE WITH THE FINE 120.00 "
                + "PROPORTION OF SPECIFIED CONTROLLED DISQUALIFIED FROM DRIVING -";

        // when
        final List<ChunkedEntry> noArgChunks = new DocumentChunkingService().chunkDocument(analyzeResult, metadata);
        final List<ChunkedEntry> toggleOffChunks = new DocumentChunkingService(false).chunkDocument(analyzeResult, metadata);

        // then
        assertEquals(List.of(expected), noArgChunks.stream().map(ChunkedEntry::chunk).toList());
        assertEquals(List.of(expected), toggleOffChunks.stream().map(ChunkedEntry::chunk).toList());
    }

    private static DocumentLine lineWithPolygon(final String content,
                                                final double x0, final double y0,
                                                final double x1, final double y1) {
        final DocumentLine line = mock(DocumentLine.class);
        when(line.getContent()).thenReturn(content);
        // lenient: the toggle-off path must not need the geometry
        lenient().when(line.getPolygon()).thenReturn(List.of(x0, y0, x1, y0, x1, y1, x0, y1));
        return line;
    }
}
