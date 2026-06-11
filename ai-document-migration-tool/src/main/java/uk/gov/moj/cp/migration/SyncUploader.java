package uk.gov.moj.cp.migration;

import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_INDEX;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_VECTOR;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_URL;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.PAGE_NUMBER;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.models.IndexDocumentsOptions;
import com.azure.search.documents.models.IndexingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous upload path: uploads each page via the target {@link SearchClient} and blocks until it is
 * indexed before the worker reads the next page. This bounds in-flight memory to {@code workers × pageSize}
 * regardless of heap size (no producer-side backlog), which is what makes the migration safe on a
 * memory-constrained host. Per-document failures are counted (non-fatal), matching the async path; a hard
 * request failure (after SDK retries) propagates and aborts the run via the watchdog.
 */
final class SyncUploader implements DocumentUploader {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncUploader.class);

    private final SearchClient target;
    private final AtomicLong succeeded;
    private final AtomicLong failed;

    SyncUploader(final SearchClient target, final AtomicLong succeeded, final AtomicLong failed) {
        this.target = target;
        this.succeeded = succeeded;
        this.failed = failed;
    }

    @Override
    public void upload(final List<ChunkedEntry> docs) {
        final List<SearchDocument> batch = new ArrayList<>(docs.size());
        for (final ChunkedEntry entry : docs) {
            batch.add(toSearchDocument(entry));
        }
        // setThrowOnAnyError(false): per-doc failures are reported in the results (counted, non-fatal) rather
        // than throwing, mirroring the buffered sender's onActionError handling.
        final var result = target.uploadDocumentsWithResponse(
                batch, new IndexDocumentsOptions().setThrowOnAnyError(false), Context.NONE).getValue();
        for (final IndexingResult indexed : result.getResults()) {
            if (indexed.isSucceeded()) {
                succeeded.incrementAndGet();
            } else {
                failed.incrementAndGet();
                LOGGER.error("Indexing failed for id={} (status {}): {}",
                        indexed.getKey(), indexed.getStatusCode(), indexed.getErrorMessage());
            }
        }
    }

    @Override
    public void close() {
        // Nothing buffered — each page was uploaded synchronously.
    }

    private static SearchDocument toSearchDocument(final ChunkedEntry entry) {
        final SearchDocument document = new SearchDocument();
        document.put(ID, entry.id());
        document.put(CHUNK, entry.chunk());
        document.put(CHUNK_VECTOR, entry.chunkVector());
        document.put(DOCUMENT_FILE_NAME, entry.documentFileName());
        document.put(DOCUMENT_ID, entry.documentId());
        document.put(PAGE_NUMBER, entry.pageNumber());
        document.put(CHUNK_INDEX, entry.chunkIndex());
        document.put(DOCUMENT_FILE_URL, entry.documentFileUrl());
        document.put(CUSTOM_METADATA, entry.customMetadata());
        return document;
    }
}
