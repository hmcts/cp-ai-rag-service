package uk.gov.moj.cp.migration.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.azure.core.http.rest.PagedIterable;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TableCopier} with the source/target {@link TableClient}s mocked — no live service.
 * Covers the key transformation ({@code toTargetEntity}: system-property stripping, partition-key override vs
 * verbatim, type fidelity), the copy loop (cap, idempotent table creation, source never written), and the
 * fail-loud RowKey-collision guard under override.
 */
class TableCopierTest {

    private static final String OVERRIDE = "consumer-123";

    // --- toTargetEntity: transformation -------------------------------------------------------------------

    @Test
    void stripsSystemPropertiesAndKeepsDataColumnsUnderOverride() {
        final TableEntity source = mock(TableEntity.class);
        when(source.getPartitionKey()).thenReturn("old-pk");
        when(source.getRowKey()).thenReturn("rk-1");
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put("PartitionKey", "old-pk");
        props.put("RowKey", "rk-1");
        props.put("Timestamp", OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        props.put("odata.etag", "W/\"datetime'2024'\"");
        props.put("DocumentStatus", "INGESTED");
        props.put("DocumentId", "rk-1");
        when(source.getProperties()).thenReturn(props);

        final TableEntity target = copier(OVERRIDE, 0).toTargetEntity(source);

        assertThat(target.getPartitionKey()).isEqualTo(OVERRIDE);
        assertThat(target.getRowKey()).isEqualTo("rk-1");
        assertThat(target.getProperties())
                .containsEntry("DocumentStatus", "INGESTED")
                .containsEntry("DocumentId", "rk-1")
                .doesNotContainKey("Timestamp")
                .doesNotContainKey("odata.etag");
    }

    @Test
    void preservesNonStringPropertyTypesAsRawObjects() {
        final OffsetDateTime when = OffsetDateTime.parse("2024-06-01T12:30:00Z");
        final UUID guid = UUID.fromString("00000000-0000-0000-0000-000000000abc");
        final TableEntity source = mock(TableEntity.class);
        when(source.getPartitionKey()).thenReturn("pk");
        when(source.getRowKey()).thenReturn("rk");
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put("ResponseGenerationTime", when);
        props.put("ResponseGenerationDuration", 4200L);
        props.put("GroundednessScore", 0.875d);
        props.put("Flagged", Boolean.TRUE);
        props.put("Correlation", guid);
        when(source.getProperties()).thenReturn(props);

        final Map<String, Object> copied = copier(null, 0).toTargetEntity(source).getProperties();

        assertThat(copied.get("ResponseGenerationTime")).isSameAs(when);
        assertThat(copied.get("ResponseGenerationDuration")).isEqualTo(4200L).isInstanceOf(Long.class);
        assertThat(copied.get("GroundednessScore")).isEqualTo(0.875d).isInstanceOf(Double.class);
        assertThat(copied.get("Flagged")).isEqualTo(Boolean.TRUE);
        assertThat(copied.get("Correlation")).isSameAs(guid);
    }

    @Test
    void stripsPerPropertyTypeAnnotationsAndAllOdataMetadataKeys() {
        // On read the SDK leaves "<col>@odata.type" annotations and odata.* metadata keys in the properties map.
        // Re-sending them as literal columns produces a malformed entity the service rejects with InvalidInput,
        // so they must be dropped (the serializer regenerates the type tags from the value types on write).
        final OffsetDateTime when = OffsetDateTime.parse("2024-06-01T12:30:00Z");
        final TableEntity source = mock(TableEntity.class);
        when(source.getPartitionKey()).thenReturn("pk");
        when(source.getRowKey()).thenReturn("rk");
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put("ResponseGenerationTime", when);
        props.put("ResponseGenerationTime@odata.type", "Edm.DateTime");
        props.put("ResponseGenerationDuration", 4200L);
        props.put("ResponseGenerationDuration@odata.type", "Edm.Int64");
        props.put("odata.etag", "W/\"datetime'2024'\"");
        props.put("odata.editLink", "Table(PartitionKey='pk',RowKey='rk')");
        props.put("odata.id", "https://acct.table.core.windows.net/Table(...)");
        props.put("odata.metadata", "https://acct.table.core.windows.net/$metadata#Table");
        props.put("LlmResponse", "an answer");
        when(source.getProperties()).thenReturn(props);

        final Map<String, Object> copied = copier(OVERRIDE, 0).toTargetEntity(source).getProperties();

        // Only the real data columns survive; their raw typed values are intact.
        assertThat(copied.get("ResponseGenerationTime")).isSameAs(when);
        assertThat(copied.get("ResponseGenerationDuration")).isEqualTo(4200L);
        assertThat(copied.get("LlmResponse")).isEqualTo("an answer");
        assertThat(copied.keySet())
                .noneMatch(k -> k.endsWith("@odata.type"))
                .noneMatch(k -> k.startsWith("odata."));
    }

    @Test
    void verbatimCopyKeepsSourcePartitionKeyWhenOverrideAbsent() {
        final TableEntity target = copier(null, 0).toTargetEntity(entity("orig-pk", "rk-1"));
        assertThat(target.getPartitionKey()).isEqualTo("orig-pk");
        assertThat(target.getRowKey()).isEqualTo("rk-1");
    }

    @Test
    void skipsNullPropertyValues() {
        final TableEntity source = mock(TableEntity.class);
        when(source.getPartitionKey()).thenReturn("pk");
        when(source.getRowKey()).thenReturn("rk");
        final Map<String, Object> props = new LinkedHashMap<>();
        props.put("Reason", null);
        props.put("DocumentStatus", "FAILED");
        when(source.getProperties()).thenReturn(props);

        assertThat(copier(null, 0).toTargetEntity(source).getProperties())
                .containsEntry("DocumentStatus", "FAILED")
                .doesNotContainKey("Reason");
    }

    // --- copyAllRows: loop behaviour ----------------------------------------------------------------------

    @Test
    void copiesEveryRowAndNeverWritesToSource() {
        final TableClient source = sourceWith(entity("a", "rk-a"), entity("b", "rk-b"));
        final TableClient target = mock(TableClient.class);

        final long copied = new TableCopier(source, target, OVERRIDE, 0).copyAllRows();

        assertThat(copied).isEqualTo(2);
        verify(target, times(2)).upsertEntity(any());
        verify(source, never()).upsertEntity(any());
        verify(source, never()).createEntity(any());
        verify(source, never()).deleteEntity(any());
    }

    @Test
    void maxRecordsCapsTheNumberOfRowsCopied() {
        final TableClient source = sourceWith(
                entity("a", "1"), entity("b", "2"), entity("c", "3"), entity("d", "4"), entity("e", "5"));
        final TableClient target = mock(TableClient.class);

        final long copied = new TableCopier(source, target, OVERRIDE, 3).copyAllRows();

        assertThat(copied).isEqualTo(3);
        verify(target, times(3)).upsertEntity(any());
    }

    @Test
    void duplicateRowKeyUnderOverrideFailsLoud() {
        // Two rows that share a RowKey but live in different source partitions: collapsing to one partition
        // would make the second upsert overwrite the first, so the copy must abort.
        final TableClient source = sourceWith(entity("pk-1", "dup"), entity("pk-2", "dup"));
        final TableClient target = mock(TableClient.class);

        assertThatThrownBy(() -> new TableCopier(source, target, OVERRIDE, 0).copyAllRows())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate RowKey")
                .hasMessageContaining("dup");
    }

    @Test
    void duplicateRowKeyAllowedWhenOverrideAbsent() {
        // Without override each row keeps its own partition, so identical RowKeys are distinct entities.
        final TableClient source = sourceWith(entity("pk-1", "dup"), entity("pk-2", "dup"));
        final TableClient target = mock(TableClient.class);

        assertThatCode(() -> new TableCopier(source, target, null, 0).copyAllRows()).doesNotThrowAnyException();
        verify(target, times(2)).upsertEntity(any());
    }

    // --- ensureTargetTableExists --------------------------------------------------------------------------

    @Test
    void tableAlreadyExistsIsSwallowed() {
        final TableClient source = sourceWith(entity("a", "rk-a"));
        final TableClient target = mock(TableClient.class);
        // Build the exception before the when(...) — its nested stubbing must finish before the outer one starts.
        final TableServiceException alreadyExists = tableServiceException(TableErrorCode.TABLE_ALREADY_EXISTS);
        when(target.createTable()).thenThrow(alreadyExists);

        assertThatCode(() -> new TableCopier(source, target, OVERRIDE, 0).copyAllRows()).doesNotThrowAnyException();
        verify(target).upsertEntity(any());
    }

    @Test
    void otherCreateTableErrorIsRethrown() {
        final TableClient source = mock(TableClient.class);
        final TableClient target = mock(TableClient.class);
        final TableServiceException otherError = tableServiceException(TableErrorCode.INVALID_INPUT);
        when(target.createTable()).thenThrow(otherError);

        assertThatThrownBy(() -> new TableCopier(source, target, OVERRIDE, 0).copyAllRows())
                .isInstanceOf(TableServiceException.class);
        verify(source, never()).listEntities();
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static TableCopier copier(final String override, final long maxRecords) {
        return new TableCopier(mock(TableClient.class), mock(TableClient.class), override, maxRecords);
    }

    private static TableEntity entity(final String partitionKey, final String rowKey) {
        return new TableEntity(partitionKey, rowKey).addProperty("DocumentStatus", "INGESTED");
    }

    @SuppressWarnings("unchecked")
    private static TableClient sourceWith(final TableEntity... rows) {
        final TableClient source = mock(TableClient.class);
        final PagedIterable<TableEntity> paged = mock(PagedIterable.class);
        when(paged.iterator()).thenReturn(List.of(rows).iterator());
        when(source.listEntities()).thenReturn(paged);
        return source;
    }

    private static TableServiceException tableServiceException(final TableErrorCode code) {
        final TableServiceError error = mock(TableServiceError.class);
        when(error.getErrorCode()).thenReturn(code);
        final TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(error);
        return tse;
    }
}
