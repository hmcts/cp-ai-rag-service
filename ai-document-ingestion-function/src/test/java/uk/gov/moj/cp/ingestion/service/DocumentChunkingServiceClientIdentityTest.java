package uk.gov.moj.cp.ingestion.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;

import java.util.List;
import java.util.Map;

import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.DocumentLine;
import com.azure.ai.documentintelligence.models.DocumentPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The clientId carried on the ingestion message must be stamped onto every chunk produced for the
 * document, so the chunks can later be written to the client-scoped search field.
 */
@ExtendWith(MockitoExtension.class)
class DocumentChunkingServiceClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private AnalyzeResult analyzeResult;
    @Mock
    private DocumentPage page;
    @Mock
    private DocumentLine line;

    private DocumentChunkingService documentChunkingService;

    @BeforeEach
    void setUp() {
        documentChunkingService = new DocumentChunkingService();
        when(analyzeResult.getPages()).thenReturn(List.of(page));
        when(page.getLines()).thenReturn(List.of(line));
        when(line.getContent()).thenReturn("This is a sufficiently long line of extractable document text content.");
    }

    @Test
    @DisplayName("stamps the message client id onto every produced chunk")
    void shouldStampClientIdOntoChunks() throws Exception {
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000", "doc.pdf", Map.of("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/container/doc.pdf", "2025-10-07T10:30:45.123456Z", CLIENT_ID);

        final List<ChunkedEntry> chunks = documentChunkingService.chunkDocument(analyzeResult, metadata);

        assertThat(chunks, is(not(List.of())));
        assertThat(chunks.stream().map(ChunkedEntry::clientId).toList(), everyItem(is(CLIENT_ID)));
    }

    @Test
    @DisplayName("legacy message without a client id produces chunks with no client id")
    void shouldLeaveClientIdNull_whenLegacyMessage() throws Exception {
        final QueueIngestionMetadata metadata = new QueueIngestionMetadata(
                "123e4567-e89b-12d3-a456-426614174000", "doc.pdf", Map.of("document_type", "CONTRACT"),
                "https://storage.blob.core.windows.net/container/doc.pdf", "2025-10-07T10:30:45.123456Z");

        final List<ChunkedEntry> chunks = documentChunkingService.chunkDocument(analyzeResult, metadata);

        assertThat(chunks, is(not(List.of())));
        assertThat(chunks.stream().map(ChunkedEntry::clientId).toList(), everyItem(is((String) null)));
    }
}
