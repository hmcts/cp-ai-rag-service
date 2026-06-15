package uk.gov.moj.cp.migration.table;

import uk.gov.moj.cp.ai.client.TableClientFactory;

import com.azure.data.tables.TableClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-off CLI to copy an Azure Storage table into a new table, optionally rewriting every row's
 * {@code PartitionKey} to a fixed value — the entry point that wires {@link TableClientFactory} (connection)
 * to {@link TableCopier} (the copy engine).
 *
 * <p>Motivation: the service is going multi-tenant. Existing single-consumer rows (keyed today by
 * {@code PartitionKey == RowKey == id}) must be migrated so every row's {@code PartitionKey} becomes a fixed
 * consumer-id string, giving each consumer its own partition. A {@code PartitionKey} is immutable, so this is a
 * copy-into-new-table; the source is never mutated and a run is idempotent (safe to re-run).</p>
 *
 * <p>Connection details come from the environment via {@link TableClientFactory}
 * ({@code AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE} = {@code MANAGED_IDENTITY} (default) or
 * {@code CONNECTION_STRING}; then {@code AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT} or
 * {@code AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING}), so only the table names and the override are
 * passed as arguments.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   mvn -pl ai-document-migration-tool exec:java \
 *     -Dexec.mainClass=uk.gov.moj.cp.migration.table.TableMigrationTool \
 *     -Dexec.args="&lt;sourceTable&gt; &lt;targetTable&gt; [partitionKeyOverride] [maxRecords]"
 *
 *   # copy every row, rewriting the partition key to a fixed consumer id
 *   ... -Dexec.args="ingestionOutcome ingestionOutcomeV2 my-consumer-id"
 *
 *   # copy verbatim (keep each row's partition key), sample the first 100 rows
 *   ... -Dexec.args="ingestionOutcome ingestionOutcomeV2 - 100"
 * </pre>
 *
 * <p>{@code partitionKeyOverride} is optional; omit it (or pass a blank / {@code "-"}) to copy partition keys
 * verbatim. {@code maxRecords} caps the copy for a sample run; {@code 0} (default) copies everything.</p>
 */
public final class TableMigrationTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableMigrationTool.class);

    private TableMigrationTool() {
    }

    public static void main(final String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <sourceTable> <targetTable> [partitionKeyOverride] [maxRecords]");
        }
        final String sourceTable = args[0];
        final String targetTable = args[1];
        // Treat a blank arg or the "-" placeholder as "no override" so maxRecords can still be supplied positionally.
        final String partitionKeyOverride =
                args.length > 2 && !args[2].isBlank() && !"-".equals(args[2]) ? args[2] : null;
        final long maxRecords = args.length > 3 ? Long.parseLong(args[3]) : 0; // 0 = copy everything

        if (sourceTable.equals(targetTable)) {
            throw new IllegalArgumentException(
                    "sourceTable and targetTable must differ — this tool copies into a NEW table.");
        }

        LOGGER.info("Table copy starting — source='{}', target='{}', partitionKeyOverride={}, maxRecords={}.",
                sourceTable, targetTable,
                partitionKeyOverride != null ? "'" + partitionKeyOverride + "'" : "(none — copy verbatim)",
                maxRecords > 0 ? maxRecords : "(all)");

        final TableClient source = TableClientFactory.getInstance(sourceTable);
        final TableClient target = TableClientFactory.getInstance(targetTable);

        final long copied = new TableCopier(source, target, partitionKeyOverride, maxRecords).copyAllRows();

        if (maxRecords > 0) {
            LOGGER.info("Partial copy complete — {} row(s) copied into '{}'. This is a SAMPLE dataset; it is NOT "
                    + "the full table and must NOT be used for cutover.", copied, targetTable);
            return;
        }
        LOGGER.info("Table copy complete — {} row(s) copied into '{}'. Source '{}' is unchanged.",
                copied, targetTable, sourceTable);
    }
}
