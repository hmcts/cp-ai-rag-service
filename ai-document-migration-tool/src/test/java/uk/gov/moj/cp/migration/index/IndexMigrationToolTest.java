package uk.gov.moj.cp.migration.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.client.AISearchClientFactory;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchIndexingBufferedSender;
import com.azure.search.documents.indexes.SearchIndexClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Unit tests for the {@code main} wiring — argument parsing and which collaborators are invoked — with all
 * Azure-touching collaborators (the static {@link SearchIndexAdmin}/{@link AISearchClientFactory} factories
 * and the constructed {@link IndexCopier}) mocked. No live service is contacted.
 */
class IndexMigrationToolTest {

    private static final String ENDPOINT = "https://svc.search.windows.net";

    @Test
    void mainRejectsTooFewArguments() {
        assertThatThrownBy(() -> IndexMigrationTool.main(new String[]{"a", "b", "c", "d"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage:");
    }

    @Test
    void usageMessageDocumentsTheClientIdOverrideArgument() {
        assertThatThrownBy(() -> IndexMigrationTool.main(new String[]{"a", "b", "c"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientIdOverride");
    }

    @Test
    void clientIdOverrideArgumentIsParsedAndThreadedOntoTheCopier() throws Exception {
        assertThat(copierClientIdOverrideArg("8", "0", "cursor-x", "consumer-abc")).isEqualTo("consumer-abc");
    }

    @Test
    void blankOrDashClientIdOverrideArgumentIsTreatedAsNoOverride() throws Exception {
        assertThat(copierClientIdOverrideArg("8", "0", "cursor-x", "-")).isNull();
        assertThat(copierClientIdOverrideArg("8", "0", "cursor-x", " ")).isNull();
    }

    @Test
    void absentClientIdOverrideArgumentYieldsNoOverride() throws Exception {
        assertThat(copierClientIdOverrideArg("8", "0", "cursor-x")).isNull();
    }

    @Test
    void fullCopyRunWiresClientsRunsTheCopyThenVerifiesCountsAndPrintsCutover() throws Exception {
        final SearchIndexClient indexClient = mock(SearchIndexClient.class);
        final SearchClient sourceClient = mock(SearchClient.class);
        final SearchIndexingBufferedSender<ChunkedEntry> sender = senderMock();
        final List<List<Object>> copierCtorArgs = new ArrayList<>();

        try (MockedStatic<SearchIndexAdmin> admin = mockStatic(SearchIndexAdmin.class);
             MockedStatic<AISearchClientFactory> factory = mockStatic(AISearchClientFactory.class);
             MockedConstruction<IndexCopier> copier = mockConstruction(IndexCopier.class, (m, ctx) -> {
                 copierCtorArgs.add(new ArrayList<>(ctx.arguments()));
                 when(m.copyAllDocuments(any())).thenReturn(1234L);
             })) {

            admin.when(() -> SearchIndexAdmin.indexClient(any())).thenReturn(indexClient);
            admin.when(() -> SearchIndexAdmin.bufferedSender(any(), any(), anyInt(), any(), any())).thenReturn(sender);
            factory.when(() -> AISearchClientFactory.getInstance(any(), any())).thenReturn(sourceClient);

            IndexMigrationTool.main(new String[]{ENDPOINT, "src-index", "tgt-index", "the-alias", "/schema.json"});

            // Index created from the schema, source client resolved, copy run on a single constructed copier.
            admin.verify(() -> SearchIndexAdmin.indexClient(ENDPOINT));
            admin.verify(() -> SearchIndexAdmin.createTargetIndex(indexClient, "tgt-index", "/schema.json"));
            factory.verify(() -> AISearchClientFactory.getInstance(ENDPOINT, "src-index"));
            assertThat(copier.constructed()).hasSize(1);
            // Defaults applied: pageSize=500, workers=8, maxRecords=0; startAfterId=null; default async uploader.
            final List<Object> ctorArgs = copierCtorArgs.get(0);
            assertThat(ctorArgs.get(0)).isSameAs(sourceClient);
            assertThat(ctorArgs.get(1)).isInstanceOf(BufferedSenderUploader.class);
            assertThat(ctorArgs.subList(2, 5)).containsExactly(IndexMigrationTool.DEFAULT_PAGE_SIZE, 8, 0L);
            verify(copier.constructed().get(0)).copyAllDocuments(null);
            verify(sender).close();
            // Full copy -> verify counts and print the cutover command.
            admin.verify(() -> SearchIndexAdmin.verifyCounts(indexClient, "src-index", "tgt-index", 0L));
            admin.verify(() -> SearchIndexAdmin.logCutoverCommand(ENDPOINT, "the-alias", "tgt-index"));
        }
    }

    @Test
    void sampleCopyRunParsesWorkersMaxRecordsAndCursorAndSkipsVerificationAndCutover() throws Exception {
        final SearchClient sourceClient = mock(SearchClient.class);
        final SearchIndexingBufferedSender<ChunkedEntry> sender = senderMock();
        final List<List<Object>> copierCtorArgs = new ArrayList<>();

        try (MockedStatic<SearchIndexAdmin> admin = mockStatic(SearchIndexAdmin.class);
             MockedStatic<AISearchClientFactory> factory = mockStatic(AISearchClientFactory.class);
             MockedConstruction<IndexCopier> copier = mockConstruction(IndexCopier.class, (m, ctx) -> {
                 copierCtorArgs.add(new ArrayList<>(ctx.arguments()));
                 when(m.copyAllDocuments(any())).thenReturn(20000L);
             })) {

            admin.when(() -> SearchIndexAdmin.indexClient(any())).thenReturn(mock(SearchIndexClient.class));
            admin.when(() -> SearchIndexAdmin.bufferedSender(any(), any(), anyInt(), any(), any())).thenReturn(sender);
            factory.when(() -> AISearchClientFactory.getInstance(any(), any())).thenReturn(sourceClient);

            IndexMigrationTool.main(new String[]{
                    ENDPOINT, "src-index", "tgt-index", "the-alias", "/schema.json", "4", "20000", "cursor-9"});

            // workers=4 and maxRecords=20000 parsed onto the copier; startAfterId="cursor-9" passed to the copy.
            final List<Object> ctorArgs = copierCtorArgs.get(0);
            assertThat(ctorArgs.get(0)).isSameAs(sourceClient);
            assertThat(ctorArgs.subList(2, 5)).containsExactly(IndexMigrationTool.DEFAULT_PAGE_SIZE, 4, 20000L);
            verify(copier.constructed().get(0)).copyAllDocuments("cursor-9");
            verify(sender).close();
            // Sample run -> NO count verification and NO cutover command.
            admin.verify(() -> SearchIndexAdmin.verifyCounts(any(), any(), any(), anyLong()), never());
            admin.verify(() -> SearchIndexAdmin.logCutoverCommand(any(), any(), any()), never());
        }
    }

    @Test
    void uploaderSelectsSyncForSyncModeAndAsyncOtherwise() {
        try (MockedStatic<AISearchClientFactory> factory = mockStatic(AISearchClientFactory.class);
             MockedStatic<SearchIndexAdmin> admin = mockStatic(SearchIndexAdmin.class)) {

            factory.when(() -> AISearchClientFactory.getInstance(any(), any())).thenReturn(mock(SearchClient.class));
            admin.when(() -> SearchIndexAdmin.bufferedSender(any(), any(), anyInt(), any(), any())).thenReturn(senderMock());

            assertThat(IndexMigrationTool.uploader("sync", "e", "t", 250, new AtomicLong(), new AtomicLong()))
                    .isInstanceOf(SyncUploader.class);
            assertThat(IndexMigrationTool.uploader("ASYNC", "e", "t", 250, new AtomicLong(), new AtomicLong()))
                    .isInstanceOf(BufferedSenderUploader.class);
            assertThat(IndexMigrationTool.uploader(null, "e", "t", 250, new AtomicLong(), new AtomicLong()))
                    .isInstanceOf(BufferedSenderUploader.class); // default
        }
    }

    /**
     * Runs {@code main} with the five mandatory args followed by {@code extraArgs}, all Azure collaborators
     * mocked, and returns the {@code clientIdOverride} (6th) argument the constructed {@link IndexCopier}
     * received — asserting the tool's positional parsing threads it through correctly.
     */
    private static Object copierClientIdOverrideArg(final String... extraArgs) throws Exception {
        final List<List<Object>> copierCtorArgs = new ArrayList<>();
        final SearchIndexingBufferedSender<ChunkedEntry> sender = senderMock();

        try (MockedStatic<SearchIndexAdmin> admin = mockStatic(SearchIndexAdmin.class);
             MockedStatic<AISearchClientFactory> factory = mockStatic(AISearchClientFactory.class);
             MockedConstruction<IndexCopier> copier = mockConstruction(IndexCopier.class, (m, ctx) -> {
                 copierCtorArgs.add(new ArrayList<>(ctx.arguments()));
                 when(m.copyAllDocuments(any())).thenReturn(1L);
             })) {

            admin.when(() -> SearchIndexAdmin.indexClient(any())).thenReturn(mock(SearchIndexClient.class));
            admin.when(() -> SearchIndexAdmin.bufferedSender(any(), any(), anyInt(), any(), any())).thenReturn(sender);
            factory.when(() -> AISearchClientFactory.getInstance(any(), any())).thenReturn(mock(SearchClient.class));

            final String[] base = {ENDPOINT, "src-index", "tgt-index", "the-alias", "/schema.json"};
            final String[] args = Arrays.copyOf(base, base.length + extraArgs.length);
            System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
            IndexMigrationTool.main(args);

            return copierCtorArgs.get(0).get(5); // the clientIdOverride constructor argument
        }
    }

    @SuppressWarnings("unchecked")
    private static SearchIndexingBufferedSender<ChunkedEntry> senderMock() {
        return mock(SearchIndexingBufferedSender.class);
    }
}
