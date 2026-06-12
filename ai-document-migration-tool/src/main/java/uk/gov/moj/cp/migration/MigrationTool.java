package uk.gov.moj.cp.migration;

import uk.gov.moj.cp.migration.index.IndexMigrationTool;
import uk.gov.moj.cp.migration.table.TableMigrationTool;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point (jar {@code Main-Class}) for the migration utilities. The first argument selects the tool
 * by name; the remaining arguments are forwarded to that tool verbatim.
 *
 * <p>There is deliberately <strong>no default tool</strong>: each tool performs a bulk, hard-to-undo copy, so
 * the operator must name the one they mean rather than have the container fall back to one implicitly. An empty
 * or unrecognised tool name prints the usage (listing both tools and their argument signatures) and fails.</p>
 *
 * <pre>
 *   index  &lt;endpoint&gt; &lt;sourceIndex&gt; &lt;targetIndex&gt; &lt;aliasName&gt; &lt;schemaResourcePath&gt; [workers] [maxRecords] [startAfterId]
 *   table  &lt;sourceTable&gt; &lt;targetTable&gt; [partitionKeyOverride] [maxRecords]
 *
 *   # via the packaged jar / container (ENTRYPOINT is "java -jar app.jar")
 *   docker run --rm --env-file env ai-rag-migration:1 table srcTable tgtTable my-consumer-id
 *
 *   # via maven
 *   mvn -pl ai-document-migration-tool exec:java -Dexec.args="table srcTable tgtTable my-consumer-id"
 * </pre>
 */
public final class MigrationTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationTool.class);

    private static final String USAGE = """
            Usage: <tool> <tool-args...>  (no default — name the tool explicitly)
              index  <endpoint> <sourceIndex> <targetIndex> <aliasName> <schemaResourcePath> [workers] [maxRecords] [startAfterId]
              table  <sourceTable> <targetTable> [partitionKeyOverride] [maxRecords]""";

    private MigrationTool() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0 || isHelp(args[0])) {
            if (args.length == 0) {
                throw new IllegalArgumentException("No migration tool specified.\n" + USAGE);
            }
            LOGGER.info(USAGE);
            return;
        }

        final String tool = args[0];
        final String[] toolArgs = Arrays.copyOfRange(args, 1, args.length);
        LOGGER.info("Running migration tool '{}'.", tool);

        switch (tool) {
            case "index" -> IndexMigrationTool.main(toolArgs);
            case "table" -> TableMigrationTool.main(toolArgs);
            default -> throw new IllegalArgumentException("Unknown migration tool '" + tool + "'.\n" + USAGE);
        }
    }

    private static boolean isHelp(final String arg) {
        return "help".equals(arg) || "-h".equals(arg) || "--help".equals(arg);
    }
}
