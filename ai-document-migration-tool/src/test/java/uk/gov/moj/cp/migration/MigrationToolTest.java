package uk.gov.moj.cp.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import uk.gov.moj.cp.migration.index.IndexMigrationTool;
import uk.gov.moj.cp.migration.table.TableMigrationTool;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for the subcommand dispatcher: it selects a tool by its first argument and forwards the rest, with
 * no default tool. The per-tool {@code main}s are mocked so nothing touches a live service.
 */
class MigrationToolTest {

    @Test
    void noArgumentsFailsWithUsageListingBothTools() {
        assertThatThrownBy(() -> MigrationTool.main(new String[]{}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No migration tool specified")
                .hasMessageContaining("index")
                .hasMessageContaining("table");
    }

    @Test
    void unknownToolFailsWithUsage() {
        assertThatThrownBy(() -> MigrationTool.main(new String[]{"bogus", "x"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown migration tool 'bogus'")
                .hasMessageContaining("index")
                .hasMessageContaining("table");
    }

    @Test
    void indexSubcommandForwardsRemainingArgsToIndexTool() throws Exception {
        try (MockedStatic<IndexMigrationTool> index = mockStatic(IndexMigrationTool.class)) {
            MigrationTool.main(new String[]{"index", "ep", "src", "tgt", "alias", "/schema.json", "8"});
            index.verify(() -> IndexMigrationTool.main(new String[]{"ep", "src", "tgt", "alias", "/schema.json", "8"}));
        }
    }

    @Test
    void tableSubcommandForwardsRemainingArgsToTableTool() throws Exception {
        try (MockedStatic<TableMigrationTool> table = mockStatic(TableMigrationTool.class)) {
            MigrationTool.main(new String[]{"table", "srcTbl", "tgtTbl", "consumer-1"});
            table.verify(() -> TableMigrationTool.main(new String[]{"srcTbl", "tgtTbl", "consumer-1"}));
        }
    }

    @Test
    void helpFlagPrintsUsageAndDoesNotThrow() {
        assertThatCode(() -> MigrationTool.main(new String[]{"--help"})).doesNotThrowAnyException();
    }
}
