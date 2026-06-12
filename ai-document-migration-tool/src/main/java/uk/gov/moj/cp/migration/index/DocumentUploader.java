package uk.gov.moj.cp.migration.index;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.List;

/**
 * Sink for copied document batches. Two implementations let the migration trade throughput against memory:
 * {@link BufferedSenderUploader} (async, higher throughput, unbounded producer-side buffering) and
 * {@link SyncUploader} (per-page synchronous upload, bounding in-flight memory to {@code workers × pageSize}).
 */
interface DocumentUploader {

    /** Uploads a batch of documents. May buffer (async) or block until indexed (sync). */
    void upload(List<ChunkedEntry> docs);

    /** Flushes any buffered work and releases resources. No-op for the synchronous uploader. */
    void close();
}
