package uk.gov.moj.cp.ai.service.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_METADATA;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_SUPERSEDED_DOCUMENTS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_EXPIRES_AT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_OWNER;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.IdempotencyStatusStore;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;

import java.time.OffsetDateTime;
import java.util.List;

import com.azure.data.tables.models.TableEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentIngestionOutcomeTableServiceTest {

    private TableService mockTableService;

    @BeforeEach
    public void setUp() {
        mockTableService = mock(TableService.class);
    }

    @Test
    @DisplayName("Throws exception when table name is null or empty")
    void throwsExceptionWhenConnectionStringOrTableNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new DocumentIngestionOutcomeTableService((String) null));
        assertEquals("Table name cannot be null or empty.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new DocumentIngestionOutcomeTableService(""));
        assertEquals("Table name cannot be null or empty.", exception.getMessage());
    }

    @Test
    @DisplayName("Successfully inserts document outcome with documentId as partitionKey/rowKey")
    void successfullyInsertsDocumentOutcomeWithDocumentIdAsKey() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert(null, "docId", "docName", "metadata","doc1,doc2","status", "reason");

        final ArgumentCaptor<TableEntity> tableEntityCaptor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertIntoTable(tableEntityCaptor.capture());

        final TableEntity actualTableEntity = tableEntityCaptor.getValue();
        assertThat(actualTableEntity.getPartitionKey()).isEqualTo("docId");
        assertThat(actualTableEntity.getRowKey()).isEqualTo("docId");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_ID)).isEqualTo("docId");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_FILE_NAME)).isEqualTo("docName");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_METADATA)).isEqualTo("metadata");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_SUPERSEDED_DOCUMENTS)).isEqualTo("doc1,doc2");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_STATUS)).isEqualTo("status");
        assertThat(actualTableEntity.getProperty(TC_REASON)).isEqualTo("reason");
    }

    @Test
    @DisplayName("Successfully get document outcome by documentId")
    void successfullyGetDocumentOutcomeByDocumentId() throws DuplicateRecordException, EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final TableEntity mockTableEntity = mock(TableEntity.class);
        when(mockTableService.getFirstDocumentMatching("docId", "docId")).thenReturn(mockTableEntity);

        final DocumentIngestionOutcome document = service.getDocumentById(null, "docId");

        verify(mockTableService).getFirstDocumentMatching("docId", "docId");
    }

    @Test
    @DisplayName("Successfully upserts document using the documentId")
    void successfullyUpsertsDocument() throws EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final String docId = "docId";
        final TableEntity entity = new TableEntity("partitionKey", "rowKey")
                .addProperty("DocumentId", docId)
                .addProperty("DocumentFileName", "docName")
                .addProperty("DocumentStatus", "status")
                .addProperty("Reason", "reason");
        when(mockTableService.getFirstDocumentMatching(docId, docId)).thenReturn(entity);

        service.upsertDocument(null, docId, "status", "reason");

        verify(mockTableService).upsertIntoTable(any(TableEntity.class));
    }

    // ---- IdempotencyStatusStore implementation ----

    @Test
    @DisplayName("recordOutcomeFenced conditionally MERGEs only the status and reason columns")
    void recordOutcomeFencedMergesOnlyStatusAndReason() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.recordOutcomeFenced(null, "docId", "INGESTION_SUCCESS", "done", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"claimed\""));
        final TableEntity entity = captor.getValue();
        assertEquals("docId", entity.getPartitionKey());
        assertEquals("docId", entity.getRowKey());
        assertEquals("INGESTION_SUCCESS", entity.getProperty(TC_DOCUMENT_STATUS));
        assertEquals("done", entity.getProperty(TC_REASON));
        // MERGE-only column footprint: no other document columns are rewritten
        assertThat(entity.getProperties()).doesNotContainKeys(
                TC_DOCUMENT_FILE_NAME, TC_DOCUMENT_METADATA, TC_DOCUMENT_SUPERSEDED_DOCUMENTS, TC_DOCUMENT_ID);
    }

    @Test
    @DisplayName("readForClaim maps status, etag and lease columns into a LeaseSnapshot")
    void readForClaimMapsSnapshot() throws EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:00:00Z");
        final TableEntity entity = mock(TableEntity.class);
        when(entity.getProperty(TC_DOCUMENT_STATUS)).thenReturn("AWAITING_INGESTION");
        when(entity.getProperty(TC_LEASE_EXPIRES_AT)).thenReturn(expiry);
        when(entity.getProperty(TC_LEASE_OWNER)).thenReturn("owner-1");
        when(entity.getETag()).thenReturn("W/\"read\"");
        when(mockTableService.getFirstDocumentMatching("docId", "docId")).thenReturn(entity);

        assertEquals(new LeaseSnapshot("AWAITING_INGESTION", "W/\"read\"", expiry, "owner-1"),
                service.readForClaim(null, "docId"));
    }

    @Test
    @DisplayName("isTerminal classifies SUCCESS, FAILED and FILE_SIZE_OVER_LIMIT as terminal; AWAITING_* as not")
    void isTerminalClassifiesStatuses() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        assertThat(service.isTerminal("INGESTION_SUCCESS")).isTrue();
        assertThat(service.isTerminal("INGESTION_FAILED")).isTrue();
        assertThat(service.isTerminal("FILE_SIZE_OVER_LIMIT")).isTrue();
        assertThat(service.isTerminal("AWAITING_UPLOAD")).isFalse();
        assertThat(service.isTerminal("AWAITING_INGESTION")).isFalse();
        assertThat(service.isTerminal(null)).isFalse();
    }

    @Test
    @DisplayName("claimLease conditionally writes only the lease columns and returns the new etag")
    void claimLeaseWritesOnlyLeaseColumns() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:10:00Z");
        when(mockTableService.updateEntityIfUnchanged(any(TableEntity.class), org.mockito.ArgumentMatchers.eq("W/\"read\"")))
                .thenReturn("W/\"claimed\"");

        assertEquals("W/\"claimed\"", service.claimLease(null, "docId", "W/\"read\"", "owner-1", expiry));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"read\""));
        final TableEntity entity = captor.getValue();
        assertEquals("owner-1", entity.getProperty(TC_LEASE_OWNER));
        assertEquals(expiry, entity.getProperty(TC_LEASE_EXPIRES_AT));
        assertThat(entity.getProperties()).doesNotContainKeys(TC_DOCUMENT_STATUS);
    }

    @Test
    @DisplayName("createClaimedRow inserts a minimal AWAITING_INGESTION row with the lease applied")
    void createClaimedRowInsertsMinimalRow() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:10:00Z");
        when(mockTableService.insertReturningEtag(any(TableEntity.class))).thenReturn("W/\"created\"");

        assertEquals("W/\"created\"", service.createClaimedRow(null, "docId", "owner-1", expiry));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertReturningEtag(captor.capture());
        final TableEntity entity = captor.getValue();
        assertEquals("AWAITING_INGESTION", entity.getProperty(TC_DOCUMENT_STATUS));
        assertEquals("owner-1", entity.getProperty(TC_LEASE_OWNER));
        assertEquals(expiry, entity.getProperty(TC_LEASE_EXPIRES_AT));
    }

    @Test
    @DisplayName("releaseLease marks the lease reclaimable via the epoch sentinel and swallows rejection")
    void releaseLeaseMarksLeaseReclaimableAndSwallowsRejection() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.releaseLease(null, "docId", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"claimed\""));
        assertEquals(IdempotencyStatusStore.LEASE_RELEASED, captor.getValue().getProperty(TC_LEASE_EXPIRES_AT));

        // best-effort: a rejected release must not throw
        org.mockito.Mockito.doThrow(new EtagMismatchException("etag changed"))
                .when(mockTableService).updateEntityIfUnchanged(any(TableEntity.class), org.mockito.ArgumentMatchers.eq("W/\"stale\""));
        service.releaseLease(null, "docId", "W/\"stale\"");
    }

    // ---- Client-aware partition keying ----

    private static final String CLIENT_A = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_B = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("insert writes the row under the clientId partition when a clientId is supplied")
    void insertPartitionsByClientId() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert(CLIENT_A, "docId", "docName", "metadata", "", "status", "reason");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertIntoTable(captor.capture());
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
        assertEquals("docId", captor.getValue().getRowKey());
    }

    @Test
    @DisplayName("Two clients sharing one documentId are stored under distinct partition keys")
    void twoClientsSharingDocumentIdCoexistUnderDistinctPartitions() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert(CLIENT_A, "shared-doc", "n", "m", "", "s", "r");
        service.insert(CLIENT_B, "shared-doc", "n", "m", "", "s", "r");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService, org.mockito.Mockito.times(2)).insertIntoTable(captor.capture());
        final List<TableEntity> entities = captor.getAllValues();
        assertEquals(CLIENT_A, entities.get(0).getPartitionKey());
        assertEquals(CLIENT_B, entities.get(1).getPartitionKey());
        assertEquals(entities.get(0).getRowKey(), entities.get(1).getRowKey());
    }

    @Test
    @DisplayName("getDocumentById scopes the lookup to the supplied client partition")
    void getDocumentByIdScopesToClientPartition() throws EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.getDocumentById(CLIENT_A, "docId");

        verify(mockTableService).getFirstDocumentMatching(CLIENT_A, "docId");
    }

    @Test
    @DisplayName("readForClaim scopes the lookup to the supplied client partition")
    void readForClaimScopesToClientPartition() throws EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.readForClaim(CLIENT_A, "docId");

        verify(mockTableService).getFirstDocumentMatching(CLIENT_A, "docId");
    }

    @Test
    @DisplayName("claimLease writes the lease into the supplied client partition")
    void claimLeasePartitionsByClientId() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.claimLease(CLIENT_A, "docId", "W/\"read\"", "owner-1", OffsetDateTime.parse("2026-07-14T12:10:00Z"));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"read\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("createClaimedRow writes the defensive row into the supplied client partition")
    void createClaimedRowPartitionsByClientId() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.createClaimedRow(CLIENT_A, "docId", "owner-1", OffsetDateTime.parse("2026-07-14T12:10:00Z"));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertReturningEtag(captor.capture());
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("recordOutcomeFenced writes the fenced outcome into the supplied client partition")
    void recordOutcomeFencedPartitionsByClientId() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.recordOutcomeFenced(CLIENT_A, "docId", "INGESTION_SUCCESS", "done", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"claimed\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("releaseLease marks the reclaimable sentinel in the supplied client partition")
    void releaseLeasePartitionsByClientId() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.releaseLease(CLIENT_A, "docId", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).updateEntityIfUnchanged(captor.capture(), org.mockito.ArgumentMatchers.eq("W/\"claimed\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
        assertEquals(IdempotencyStatusStore.LEASE_RELEASED, captor.getValue().getProperty(TC_LEASE_EXPIRES_AT));
    }

    @Test
    @DisplayName("A blank clientId falls back to the row key as the partition key")
    void blankClientIdFallsBackToRowKeyPartition() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert("", "docId", "docName", "metadata", "", "status", "reason");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertIntoTable(captor.capture());
        assertEquals("docId", captor.getValue().getPartitionKey());
    }
}
