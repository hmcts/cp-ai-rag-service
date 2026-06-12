package uk.gov.moj.cp.migration.table;

import static java.lang.String.format;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies every entity from a source Azure Storage table into a (new) target table, optionally rewriting each
 * row's {@code PartitionKey} to a fixed value. The {@code RowKey} and all data columns are preserved.
 *
 * <p>An Azure Table Storage {@code PartitionKey} is part of the entity's immutable primary key — there is no
 * in-place update. The only way to "change" it is to write a new entity under the new key. This tool therefore
 * does a <strong>copy into a new table</strong>: it reads the source and upserts a transformed copy into the
 * target, and <strong>never mutates the source</strong>. That makes a run non-destructive (roll back simply by
 * continuing to use the source), idempotent (re-runnable — upsert keyed by {@code (PartitionKey, RowKey)}), and
 * safe to validate before cutover.</p>
 *
 * <p>The motivating use case is multi-tenancy: existing single-consumer rows (today keyed by
 * {@code PartitionKey == RowKey == id}) are migrated so every row's {@code PartitionKey} becomes a fixed
 * consumer-id string, giving each consumer its own partition going forward.</p>
 *
 * <p><strong>Single-threaded by design.</strong> Unlike the search-index copier (which shards because the index
 * holds hundreds of thousands of vectors and Azure AI Search caps {@code $skip} at 100k), these are small
 * operational status tables, {@link TableClient#listEntities()} transparently follows continuation tokens, and
 * the copy is idempotent — so a plain sequential pass is the right altitude.</p>
 *
 * <p><strong>Type fidelity.</strong> Property values read from the source already carry their correct Java/EDM
 * types ({@code OffsetDateTime}, {@code Long}, {@code Double}, {@code Boolean}, {@code UUID}, {@code byte[]});
 * they are copied as the raw objects and re-upserted with the same types. Decimal columns are stored by the
 * service as {@code Edm.Double} (EDM has no decimal type), so they round-trip as doubles. The copy deliberately
 * performs <em>no</em> {@code toString}/re-typing — that would corrupt non-string columns into strings.</p>
 */
final class TableCopier {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableCopier.class);

    /**
     * Entity-key and service-managed properties returned by {@link TableEntity#getProperties()} that must not be
     * carried over as data columns: the key fields come from the {@code new TableEntity(pk, rk)} constructor, and
     * {@code Timestamp} is owned by the service.
     */
    private static final Set<String> KEY_PROPERTIES = Set.of("PartitionKey", "RowKey", "Timestamp");

    /**
     * Prefix of the OData metadata keys the SDK puts in the properties map on read ({@code odata.etag},
     * {@code odata.metadata}, {@code odata.editLink}, {@code odata.id}, {@code odata.type}). Carrying any of
     * these over would corrupt the write (e.g. the source etag would scope the upsert to a stale, unrelated etag).
     */
    private static final String ODATA_METADATA_PREFIX = "odata.";

    /**
     * Suffix of the per-property EDM type annotations the SDK leaves in the properties map on read (e.g.
     * {@code "ResponseGenerationTime@odata.type" -> "Edm.DateTime"}). These must be dropped: the serializer
     * regenerates the correct annotation from each value's runtime type on write, so re-adding the read-back
     * annotation as a literal column produces a malformed entity the service rejects with {@code InvalidInput}.
     * A real column name can contain neither {@code .} nor {@code @}, so this never drops genuine data.
     */
    private static final String ODATA_TYPE_SUFFIX = "@odata.type";

    /** Emit a progress line every this many rows. */
    private static final long LOG_EVERY = 500;

    private final TableClient source;
    private final TableClient target;
    /** Fixed PartitionKey to assign to every copied row, or {@code null} to copy the source PartitionKey verbatim. */
    private final String partitionKeyOverride;
    /** Cap on the number of rows copied; {@code 0} = copy everything. */
    private final long maxRecords;

    TableCopier(final TableClient source, final TableClient target,
                final String partitionKeyOverride, final long maxRecords) {
        this.source = source;
        this.target = target;
        this.partitionKeyOverride = partitionKeyOverride;
        this.maxRecords = maxRecords;
    }

    /**
     * Streams the source table and upserts a copy of every row into the target.
     *
     * @return the number of rows copied
     */
    long copyAllRows() {
        ensureTargetTableExists();

        // Only meaningful under override: all copied rows land in one partition, so a repeated RowKey would make
        // the second upsert silently overwrite the first. Detect and fail loud rather than lose data.
        final Set<String> seenRowKeys = partitionKeyOverride != null ? new HashSet<>() : null;

        long copied = 0;
        for (final TableEntity sourceRow : source.listEntities()) {
            if (maxRecords > 0 && copied >= maxRecords) {
                LOGGER.info("Reached maxRecords cap ({}); stopping.", maxRecords);
                break;
            }
            copyRow(sourceRow, seenRowKeys);
            copied++;
            if (copied % LOG_EVERY == 0) {
                LOGGER.info("Copied {} rows...", copied);
            }
        }

        LOGGER.info("Copy finished — {} row(s) copied from '{}' to '{}'{}.",
                copied, source.getTableName(), target.getTableName(),
                partitionKeyOverride != null ? " with partition key overridden to '" + partitionKeyOverride + "'" : "");
        return copied;
    }

    private void copyRow(final TableEntity sourceRow, final Set<String> seenRowKeys) {
        if (seenRowKeys != null && !seenRowKeys.add(sourceRow.getRowKey())) {
            throw new IllegalStateException(format(
                    "Duplicate RowKey '%s' under partition-key override: collapsing partitions would overwrite an "
                            + "already-copied row. Override is unsafe for table '%s' (its RowKeys are not unique). "
                            + "Source is unchanged.",
                    sourceRow.getRowKey(), source.getTableName()));
        }
        target.upsertEntity(toTargetEntity(sourceRow));
    }

    /**
     * Builds the target entity: the (possibly overridden) PartitionKey and the original RowKey via the
     * constructor, then every source data column copied as its raw value. System properties and nulls are
     * skipped.
     */
    TableEntity toTargetEntity(final TableEntity sourceRow) {
        final String targetPartitionKey =
                partitionKeyOverride != null ? partitionKeyOverride : sourceRow.getPartitionKey();
        final TableEntity targetRow = new TableEntity(targetPartitionKey, sourceRow.getRowKey());
        for (final Map.Entry<String, Object> property : sourceRow.getProperties().entrySet()) {
            if (!isKeyOrMetadata(property.getKey()) && property.getValue() != null) {
                targetRow.addProperty(property.getKey(), property.getValue());
            }
        }
        return targetRow;
    }

    /**
     * True for the entity-key/system properties and the OData metadata + per-property type-annotation keys that
     * {@link TableEntity#getProperties()} returns on read — none of which may be re-sent as data columns.
     */
    private static boolean isKeyOrMetadata(final String key) {
        return KEY_PROPERTIES.contains(key)
                || key.startsWith(ODATA_METADATA_PREFIX)
                || key.endsWith(ODATA_TYPE_SUFFIX);
    }

    /** Idempotent create: ignore "already exists" so a re-run (or a pre-created target) is fine. */
    private void ensureTargetTableExists() {
        try {
            target.createTable();
            LOGGER.info("Created target table '{}'.", target.getTableName());
        } catch (final TableServiceException tse) {
            if (tse.getValue() != null && tse.getValue().getErrorCode() == TableErrorCode.TABLE_ALREADY_EXISTS) {
                LOGGER.info("Target table '{}' already exists — reusing it.", target.getTableName());
            } else {
                throw tse;
            }
        }
    }
}
