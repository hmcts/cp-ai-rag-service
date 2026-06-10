package uk.gov.moj.cp.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.List;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchIndexingBufferedSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class IndexCopierTest {

    @Test
    void formatEtaReturnsUnknownBeforeAnyProgressIsMeasurable() {
        assertThat(IndexCopier.formatEta(0, 100, 1_000_000_000L)).isEqualTo("unknown");
        assertThat(IndexCopier.formatEta(100, 100, 0)).isEqualTo("unknown");
    }

    @Test
    void formatEtaProjectsThisRunThroughputOverRemainingRecords() {
        assertThat(IndexCopier.formatEta(100, 100, 100_000_000_000L)).isEqualTo("00:01:40");
        assertThat(IndexCopier.formatEta(1, 7200, 1_000_000_000L)).isEqualTo("02:00:00");
        assertThat(IndexCopier.formatEta(500, 0, 5_000_000_000L)).isEqualTo("00:00:00");
    }

    @Test
    void copyAllDocumentsStreamsEachPageToSenderAndAdvancesCursorUntilPartialPage() {
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mockSender();
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), sender, 2, 1, 0));

        final List<ChunkedEntry> page1 = List.of(chunk("id-1"), chunk("id-2")); // full page -> continue
        final List<ChunkedEntry> page2 = List.of(chunk("id-3"));                // partial page -> stop
        doReturn(page1).doReturn(page2).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(3);

        // Cursor: first page starts at null, second resumes after the last id of page 1.
        final ArgumentCaptor<String> cursor = ArgumentCaptor.forClass(String.class);
        final InOrder order = inOrder(copier, sender);
        order.verify(copier).readPage(any(), cursor.capture());
        order.verify(sender).addUploadActions(page1); // plain (async, non-fatal) flush
        order.verify(copier).readPage(any(), cursor.capture());
        order.verify(sender).addUploadActions(page2);
        assertThat(cursor.getAllValues()).containsExactly(null, "id-2");
    }

    @Test
    void copyAllDocumentsStopsAtTheMaxRecordsCapAndTrimsTheLastPage() {
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mockSender();
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), sender, 2, 1, 3));

        final ChunkedEntry c1 = chunk("id-1");
        final ChunkedEntry c2 = chunk("id-2");
        final ChunkedEntry c3 = chunk("id-3");
        final ChunkedEntry c4 = chunk("id-4");
        doReturn(List.of(c1, c2)).doReturn(List.of(c3, c4)).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(3); // capped at maxRecords=3
        verify(sender).addUploadActions(List.of(c1, c2)); // first full page
        verify(sender).addUploadActions(List.of(c3));     // second page trimmed to the remaining budget (1)
    }

    @Test
    void copyAllDocumentsSkipsChunksWithMissingOrWrongSizeVectorBeforeSubmitting() {
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mockSender();
        // pageSize (5) > page (3) so the single page is "partial" and the read loop terminates after it.
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), sender, 5, 1, 0));

        final ChunkedEntry valid = chunk("id-1");                              // 3072-dim vector
        final ChunkedEntry wrongSize = chunkWithVector("id-2", List.of(0.0f)); // too short
        final ChunkedEntry nullVector = chunkWithVector("id-3", null);         // missing
        doReturn(List.of(valid, wrongSize, nullVector)).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isEqualTo(1);
        verify(sender).addUploadActions(List.of(valid)); // only the valid chunk is streamed
    }

    @Test
    void copyAllDocumentsSubmitsNothingWhenSourceIsEmpty() {
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mockSender();
        final IndexCopier copier = spy(new IndexCopier(mock(SearchClient.class), sender, 2, 1, 0));

        doReturn(List.of()).when(copier).readPage(any(), any());

        final long submitted = copier.copyAllDocuments(null);

        assertThat(submitted).isZero();
        verifyNoInteractions(sender);
    }

    @SuppressWarnings("unchecked")
    private static SearchIndexingBufferedSender<ChunkedEntry> mockSender() {
        return mock(SearchIndexingBufferedSender.class);
    }

    private static ChunkedEntry chunk(final String id) {
        return chunkWithVector(id, Collections.nCopies(IndexCopier.VECTOR_DIMENSIONS, 0.0f));
    }

    private static ChunkedEntry chunkWithVector(final String id, final List<Float> vector) {
        return ChunkedEntry.builder().id(id).chunkVector(vector).build();
    }
}
