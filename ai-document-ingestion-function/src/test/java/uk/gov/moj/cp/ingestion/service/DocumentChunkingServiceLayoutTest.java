package uk.gov.moj.cp.ingestion.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
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
 * DD-43810: with layout-aware page text enabled, a two-column PNC-style page keeps each offence next
 * to its own sentence on tab-delimited rows, and page numbers / chunk metadata stay the same as the
 * toggle-off run. Fixture text is generic and fictional.
 */
@ExtendWith(MockitoExtension.class)
class DocumentChunkingServiceLayoutTest {

    private static final double LINE_HEIGHT = 0.12;
    private static final double LEFT_MIN_X = 0.5;
    private static final double LEFT_MAX_X = 3.0;
    private static final double RIGHT_MIN_X = 4.0;
    private static final double RIGHT_MAX_X = 5.5;

    private static final String DOCUMENT_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String DOCUMENT_NAME = "previous-convictions.pdf";
    private static final String BLOB_URL = "https://storage.blob.core.windows.net/container/previous-convictions.pdf";
    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";

    @Mock
    private AnalyzeResult analyzeResult;

    private QueueIngestionMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new QueueIngestionMetadata(DOCUMENT_ID, DOCUMENT_NAME, Map.of("document_type", "PNC"),
                BLOB_URL, "2025-10-07T10:30:45.123456Z", CLIENT_ID);
    }

    @Test
    @DisplayName("AC-6: two-column PNC-style page keeps each offence and its sentence on tab-delimited rows")
    void shouldKeepOffenceAndSentenceOnTabDelimitedRows() throws Exception {
        final List<DocumentPage> pages = List.of(twoColumnConvictionsPage());
        when(analyzeResult.getPages()).thenReturn(pages);

        final List<ChunkedEntry> chunks = new DocumentChunkingService(true).chunkDocument(analyzeResult, metadata);

        final String expectedPageText = "RECORD OF PREVIOUS CONVICTIONS\n"
                + "OFFENCE\tSENTENCE\n"
                + "1. DRIVE A MOTOR VEHICLE WITH THE\tFINE 120.00\n"
                + "PROPORTION OF SPECIFIED CONTROLLED\tDISQUALIFIED FROM DRIVING -\n"
                + "DRUG ABOVE THE SPECIFIED LIMIT\t12 MONTHS";
        assertEquals(List.of(expectedPageText), chunks.stream().map(ChunkedEntry::chunk).toList());
    }

    @Test
    @DisplayName("AC-7: page numbers and chunk metadata match the toggle-off run")
    void shouldKeepPageNumbersAndMetadataSameAsToggleOff() throws Exception {
        final List<DocumentPage> pages = List.of(twoColumnConvictionsPage(), secondPage());
        when(analyzeResult.getPages()).thenReturn(pages);

        final List<ChunkedEntry> off = new DocumentChunkingService(false).chunkDocument(analyzeResult, metadata);
        final List<ChunkedEntry> on = new DocumentChunkingService(true).chunkDocument(analyzeResult, metadata);

        // sanity: both pages produced chunks in the baseline run
        assertThat(off.stream().map(ChunkedEntry::pageNumber).toList(), hasItem(1));
        assertThat(off.stream().map(ChunkedEntry::pageNumber).toList(), hasItem(2));

        assertEquals(off.stream().map(ChunkedEntry::pageNumber).toList(),
                on.stream().map(ChunkedEntry::pageNumber).toList());

        final List<KeyValuePair> expectedCustomMetadata = List.of(new KeyValuePair("document_type", "PNC"));
        for (final ChunkedEntry chunk : on) {
            assertEquals(DOCUMENT_ID, chunk.documentId());
            assertEquals(DOCUMENT_NAME, chunk.documentFileName());
            assertEquals(BLOB_URL, chunk.documentFileUrl());
            assertEquals(CLIENT_ID, chunk.clientId());
            assertEquals(expectedCustomMetadata, chunk.customMetadata());
        }
        for (final ChunkedEntry chunk : off) {
            assertEquals(expectedCustomMetadata, chunk.customMetadata());
        }
    }

    @Test
    @DisplayName("Rotated page: the page angle reaches the helper and every separator is a newline")
    void shouldNewlineJoinRotatedPage() throws Exception {
        final DocumentPage page = secondPage();
        when(page.getAngle()).thenReturn(90.8);
        when(analyzeResult.getPages()).thenReturn(List.of(page));

        final List<ChunkedEntry> chunks = new DocumentChunkingService(true).chunkDocument(analyzeResult, metadata);

        assertEquals(List.of("2. FAIL TO SURRENDER TO CUSTODY AT\nCONDITIONAL DISCHARGE\nAPPOINTED TIME\n12 MONTHS"),
                chunks.stream().map(ChunkedEntry::chunk).toList());
    }

    /**
     * Rows as Document Intelligence returns them for a two-column printout: the offence (left) is
     * followed by its sentence (right) on the same row.
     */
    private static DocumentPage twoColumnConvictionsPage() {
        final List<DocumentLine> lines = List.of(
                line("RECORD OF PREVIOUS CONVICTIONS", LEFT_MIN_X, 0.50, RIGHT_MAX_X),
                line("OFFENCE", LEFT_MIN_X, 0.80, LEFT_MAX_X),
                line("SENTENCE", RIGHT_MIN_X, 0.80, RIGHT_MAX_X),
                line("1. DRIVE A MOTOR VEHICLE WITH THE", LEFT_MIN_X, 1.00, LEFT_MAX_X),
                line("FINE 120.00", RIGHT_MIN_X, 1.00, RIGHT_MAX_X),
                line("PROPORTION OF SPECIFIED CONTROLLED", LEFT_MIN_X, 1.20, LEFT_MAX_X),
                line("DISQUALIFIED FROM DRIVING -", RIGHT_MIN_X, 1.20, RIGHT_MAX_X),
                line("DRUG ABOVE THE SPECIFIED LIMIT", LEFT_MIN_X, 1.40, LEFT_MAX_X),
                line("12 MONTHS", RIGHT_MIN_X, 1.40, RIGHT_MAX_X));
        return pageOf(lines);
    }

    private static DocumentPage secondPage() {
        final List<DocumentLine> lines = List.of(
                line("2. FAIL TO SURRENDER TO CUSTODY AT", LEFT_MIN_X, 1.00, LEFT_MAX_X),
                line("CONDITIONAL DISCHARGE", RIGHT_MIN_X, 1.00, RIGHT_MAX_X),
                line("APPOINTED TIME", LEFT_MIN_X, 1.20, LEFT_MAX_X),
                line("12 MONTHS", RIGHT_MIN_X, 1.20, RIGHT_MAX_X));
        return pageOf(lines);
    }

    private static DocumentPage pageOf(final List<DocumentLine> lines) {
        final DocumentPage page = mock(DocumentPage.class);
        when(page.getLines()).thenReturn(lines);
        return page;
    }

    private static DocumentLine line(final String content, final double minX, final double top, final double maxX) {
        final DocumentLine line = mock(DocumentLine.class);
        when(line.getContent()).thenReturn(content);
        when(line.getPolygon()).thenReturn(
                List.of(minX, top, maxX, top, maxX, top + LINE_HEIGHT, minX, top + LINE_HEIGHT));
        return line;
    }
}
