package uk.gov.moj.cp.migration;

import static java.lang.String.format;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.concurrent.atomic.AtomicLong;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchIndexingBufferedSender;
import com.azure.search.documents.indexes.SearchIndexClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-off, resumable index-to-index migration for a populated Azure AI Search index — the entry point that
 * wires together {@link SearchIndexAdmin} (client/index management) and {@link IndexCopier} (the copy
 * engine, which partitions the key space into {@link Shard}s).
 *
 * <p>Azure AI Search cannot change immutable field attributes (searchable / filterable / sortable /
 * facetable / stored / type) on an existing index, and has no native copy/backup feature. This tool
 * performs a drop-free rebuild: it creates a new index from a v2 schema, copies every document across
 * (vectors included — no re-embedding, because all fields are retrievable), verifies the document
 * counts match, then prints the alias create/repoint command for a zero-downtime cutover (the pinned
 * Java SDK has no index-alias API, so the alias step is run via the data-plane REST API / {@code az rest}).</p>
 *
 * <p><strong>Reads</strong> use keyset pagination on the key field rather than {@code $skip} (Azure caps
 * {@code $skip} at 100,000). With {@code workers > 1} the key space is split into 16 shards read
 * concurrently. <strong>Writes</strong> stream into a single thread-safe {@link SearchIndexingBufferedSender}
 * shared by all workers, which auto-batches, splits over-16 MB batches, retries throttling (429/503), and
 * flushes asynchronously.</p>
 *
 * <p>Resume: with {@code workers = 1} pass the last logged cursor id as {@code startAfterId} to continue an
 * interrupted run. With {@code workers > 1} it is ignored — just re-run, since uploads are idempotent
 * upserts keyed by {@code id}.</p>
 *
 * <p>{@code maxRecords} caps the total number of documents copied — pass e.g. {@code 20000} to copy a
 * sample for testing. It's a global cap across shards (with one worker it's the first N by id; with more,
 * ~N spread across the key space). A capped run is a deliberate subset, so it skips the full-count
 * verification and does NOT emit the alias-cutover command. {@code 0} (default) copies everything.</p>
 *
 * <p>Usage (managed-identity / {@code az login} credential is used automatically):</p>
 * <pre>
 *   mvn -pl ai-document-migration-tool exec:java \
 *     -Dexec.args="&lt;endpoint&gt; &lt;sourceIndex&gt; &lt;targetIndex&gt; &lt;aliasName&gt; &lt;schemaResourcePath&gt; [workers] [maxRecords] [startAfterId]"
 *
 *   # full copy, 8 concurrent shards
 *   ... -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8"
 *
 *   # sample copy of the first 20000 records, 8 workers
 *   ... -Dexec.args="https://my-svc.search.windows.net ai-rag-service-index ai-rag-service-index-v2 ai-rag-service-index-alias /vector-db-index-schema-v2.json 8 20000"
 * </pre>
 */
public final class IndexMigrationTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexMigrationTool.class);

    /** Read page size, and the buffered sender's initial batch size. A 3072-float vector serialises to
     *  ~25-35 KB of JSON, so ~500 keeps the initial batch under the 16 MB request limit; the sender
     *  auto-splits anyway if a batch is rejected with 413. */
    static final int DEFAULT_PAGE_SIZE = 500;

    /** Default concurrent shard readers. Capped to the shard count (16) at runtime. */
    static final int DEFAULT_WORKERS = 8;

    private IndexMigrationTool() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("Usage: <endpoint> <sourceIndex> <targetIndex> <aliasName> "
                    + "<schemaResourcePath> [workers] [maxRecords] [startAfterId]");
        }
        final String endpoint = args[0];
        final String sourceIndex = args[1];
        final String targetIndex = args[2];
        final String aliasName = args[3];
        final String schemaResource = args[4];
        final int workers = args.length > 5 ? Integer.parseInt(args[5]) : DEFAULT_WORKERS;
        final long maxRecords = args.length > 6 ? Long.parseLong(args[6]) : 0; // 0 = copy everything
        final String startAfterId = args.length > 7 ? args[7] : null;

        final SearchIndexClient indexClient = SearchIndexAdmin.indexClient(endpoint);
        SearchIndexAdmin.createTargetIndex(indexClient, targetIndex, schemaResource);

        final SearchClient sourceClient = AISearchClientFactory.getInstance(endpoint, sourceIndex);
        final AtomicLong succeeded = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final SearchIndexingBufferedSender<ChunkedEntry> sender =
                SearchIndexAdmin.bufferedSender(endpoint, targetIndex, DEFAULT_PAGE_SIZE, succeeded, failed);

        final long submitted;
        try {
            submitted = new IndexCopier(sourceClient, sender, DEFAULT_PAGE_SIZE, workers, maxRecords)
                    .copyAllDocuments(startAfterId);
        } finally {
            sender.close(); // flush all buffered actions from every worker and wait for completion
        }
        LOGGER.info("Indexing finished — submitted={}, succeeded={}, failed={}.",
                submitted, succeeded.get(), failed.get());
        if (failed.get() > 0) {
            throw new IllegalStateException(
                    format("%d document(s) failed to index. Investigate before cutover.", failed.get()));
        }

        if (maxRecords > 0) {
            // Sample copy: target intentionally holds a subset, so the full-count check and cutover don't apply.
            LOGGER.info("Partial copy complete — {} record(s) copied into '{}'. This is a SAMPLE dataset; "
                    + "it is NOT verified against the full source and must NOT be used for alias cutover.",
                    submitted, targetIndex);
            return;
        }

        SearchIndexAdmin.verifyCounts(indexClient, sourceIndex, targetIndex, succeeded.get());
        SearchIndexAdmin.logCutoverCommand(endpoint, aliasName, targetIndex);
        LOGGER.info("Migration complete. Target index '{}' is populated and verified.", targetIndex);
    }
}
