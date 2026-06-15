package uk.gov.moj.cp.migration.index;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.List;

import com.azure.search.documents.SearchIndexingBufferedSender;

/**
 * Async upload path: streams batches into the shared {@link SearchIndexingBufferedSender}, which auto-batches,
 * splits over-16 MB batches, retries throttling (429/503), and flushes in the background. Highest throughput,
 * but it buffers on the producer side — under a tight heap the backlog can grow faster than it flushes.
 */
final class BufferedSenderUploader implements DocumentUploader {

    private final SearchIndexingBufferedSender<ChunkedEntry> sender;

    BufferedSenderUploader(final SearchIndexingBufferedSender<ChunkedEntry> sender) {
        this.sender = sender;
    }

    @Override
    public void upload(final List<ChunkedEntry> docs) {
        sender.addUploadActions(docs);
    }

    @Override
    public void close() {
        sender.close(); // flush all buffered actions and wait for completion
    }
}
