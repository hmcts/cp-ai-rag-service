package uk.gov.moj.cp.migration.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.client.TableClientFactory;

import java.util.ArrayList;
import java.util.List;

import com.azure.data.tables.TableClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Unit tests for the {@code main} wiring — argument parsing and which collaborators are invoked — with the
 * static {@link TableClientFactory} and the constructed {@link TableCopier} mocked. No live service is
 * contacted.
 */
class TableMigrationToolTest {

    @Test
    void mainRejectsTooFewArguments() {
        assertThatThrownBy(() -> TableMigrationTool.main(new String[]{"only-source"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usage:");
    }

    @Test
    void mainRejectsSameSourceAndTarget() {
        assertThatThrownBy(() -> TableMigrationTool.main(new String[]{"same", "same"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void fullCopyWiresClientsAndRunsCopierWithDefaults() {
        final TableClient source = mock(TableClient.class);
        final TableClient target = mock(TableClient.class);
        final List<List<Object>> ctorArgs = new ArrayList<>();

        try (MockedStatic<TableClientFactory> factory = mockStatic(TableClientFactory.class);
             MockedConstruction<TableCopier> copier = mockConstruction(TableCopier.class, (m, ctx) -> {
                 ctorArgs.add(new ArrayList<>(ctx.arguments()));
                 when(m.copyAllRows()).thenReturn(7L);
             })) {

            factory.when(() -> TableClientFactory.getInstance("src")).thenReturn(source);
            factory.when(() -> TableClientFactory.getInstance("tgt")).thenReturn(target);

            TableMigrationTool.main(new String[]{"src", "tgt"});

            factory.verify(() -> TableClientFactory.getInstance("src"));
            factory.verify(() -> TableClientFactory.getInstance("tgt"));
            assertThat(copier.constructed()).hasSize(1);
            // (source, target, partitionKeyOverride=null, maxRecords=0)
            assertThat(ctorArgs.get(0)).containsExactly(source, target, null, 0L);
            verify(copier.constructed().get(0)).copyAllRows();
        }
    }

    @Test
    void parsesPartitionKeyOverrideAndMaxRecords() {
        runAndCaptureCtorArgs(new String[]{"src", "tgt", "consumer-123", "500"},
                args -> assertThat(args).containsExactly(mockSource(), mockTarget(), "consumer-123", 500L));
    }

    @Test
    void blankOverrideIsTreatedAsNull() {
        runAndCaptureCtorArgs(new String[]{"src", "tgt", "", "0"},
                args -> assertThat(args.get(2)).isNull());
    }

    @Test
    void dashPlaceholderOverrideIsTreatedAsNull() {
        runAndCaptureCtorArgs(new String[]{"src", "tgt", "-", "10"},
                args -> {
                    assertThat(args.get(2)).isNull();
                    assertThat(args.get(3)).isEqualTo(10L);
                });
    }

    // --- helpers ------------------------------------------------------------------------------------------

    // Sentinels so the override/maxRecords assertions can reference the exact stubbed clients by identity.
    private static final TableClient SOURCE = mock(TableClient.class);
    private static final TableClient TARGET = mock(TableClient.class);

    private static TableClient mockSource() {
        return SOURCE;
    }

    private static TableClient mockTarget() {
        return TARGET;
    }

    private void runAndCaptureCtorArgs(final String[] args, final java.util.function.Consumer<List<Object>> assertion) {
        final List<List<Object>> ctorArgs = new ArrayList<>();
        try (MockedStatic<TableClientFactory> factory = mockStatic(TableClientFactory.class);
             MockedConstruction<TableCopier> copier = mockConstruction(TableCopier.class, (m, ctx) -> {
                 ctorArgs.add(new ArrayList<>(ctx.arguments()));
                 when(m.copyAllRows()).thenReturn(0L);
             })) {

            factory.when(() -> TableClientFactory.getInstance("src")).thenReturn(SOURCE);
            factory.when(() -> TableClientFactory.getInstance("tgt")).thenReturn(TARGET);

            TableMigrationTool.main(args);

            assertThat(copier.constructed()).hasSize(1);
            assertion.accept(ctorArgs.get(0));
        }
    }
}
