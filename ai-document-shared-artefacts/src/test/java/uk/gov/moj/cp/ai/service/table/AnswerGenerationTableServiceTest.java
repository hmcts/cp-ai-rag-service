package uk.gov.moj.cp.ai.service.table;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES_FILE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_EXPIRES_AT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LEASE_OWNER;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_TIME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.IdempotencyStatusStore;
import uk.gov.moj.cp.ai.idempotency.LeaseSnapshot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.azure.data.tables.models.TableEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerGenerationTableServiceTest {
    private TableService tableService;
    private AnswerGenerationTableService service;

    @BeforeEach
    void setUp() {
        tableService = mock(TableService.class);
        service = new AnswerGenerationTableService(tableService);
    }

    @Test
    @DisplayName("Throws exception when table name is null or empty")
    void throwsExceptionWhenTableNameIsNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new AnswerGenerationTableService((String) null));
        assertThrows(IllegalArgumentException.class, () -> new AnswerGenerationTableService(""));
    }

    @Test
    @DisplayName("Successfully saves answer generation request")
    void successfullySavesAnswerGenerationRequest() throws DuplicateRecordException {
        service.saveAnswerGenerationRequest(null, "tx1", "query", "prompt", ANSWER_GENERATED);
        verify(tableService).insertIntoTable(any(TableEntity.class));
    }

    @Test
    @DisplayName("Throws exception when save fails due to duplicate record")
    void throwsExceptionWhenSaveFailsDueToDuplicateRecord() throws DuplicateRecordException {
        doThrow(new DuplicateRecordException("Insert failed")).when(tableService).insertIntoTable(any(TableEntity.class));
        assertThrows(DuplicateRecordException.class, () ->
                service.saveAnswerGenerationRequest(null, "tx2", "query", "prompt", ANSWER_GENERATED));
    }

    @Test
    @DisplayName("Returns generated answer when matching entity is found")
    void returnsGeneratedAnswerWhenMatchingEntityIsFound() throws EntityRetrievalException {
        final String transactionId = randomUUID().toString();
        TableEntity entity = new TableEntity(transactionId, transactionId);
        entity.addProperty(TC_TRANSACTION_ID, transactionId);
        entity.addProperty(TC_USER_QUERY, "query");
        entity.addProperty(TC_QUERY_PROMPT, "prompt");
        entity.addProperty(TC_CHUNKED_ENTRIES_FILE, null);
        entity.addProperty(TC_LLM_RESPONSE, null);
        entity.addProperty(TC_ANSWER_STATUS, ANSWER_GENERATED.toString());
        entity.addProperty(TC_REASON, null);
        entity.addProperty(TC_RESPONSE_GENERATION_TIME, null);
        entity.addProperty(TC_RESPONSE_GENERATION_DURATION, null);
        when(tableService.getFirstDocumentMatching(transactionId, transactionId)).thenReturn(entity);

        GeneratedAnswer answer = service.getGeneratedAnswer(null, transactionId);
        assertNotNull(answer);
        assertEquals(transactionId, answer.getTransactionId());
        assertEquals("query", answer.getUserQuery());
        assertEquals("prompt", answer.getQueryPrompt());
        assertEquals(ANSWER_GENERATED.toString(), answer.getAnswerStatus());
    }

    @Test
    @DisplayName("Returns null when no matching entity is found")
    void returnsNullWhenNoMatchingEntityIsFound() throws EntityRetrievalException {
        when(tableService.getFirstDocumentMatching("tx4", "tx4")).thenReturn(null);
        assertNull(service.getGeneratedAnswer(null, "tx4"));
    }

    @Test
    @DisplayName("Handles null properties gracefully when mapping entity to GeneratedAnswer")
    void handlesNullPropertiesGracefullyWhenMappingEntityToGeneratedAnswer() throws EntityRetrievalException {
        TableEntity entity = new TableEntity("tx5", "tx5");
        when(tableService.getFirstDocumentMatching("tx5", "tx5")).thenReturn(entity);
        GeneratedAnswer answer = service.getGeneratedAnswer(null, "tx5");
        assertNotNull(answer);
        assertNull(answer.getTransactionId());
        assertNull(answer.getUserQuery());
        assertNull(answer.getQueryPrompt());
        assertNull(answer.getAnswerStatus());
    }

    @Test
    @DisplayName("Upsert into table calls tableService.upsertIntoTable")
    void upsertIntoTableCallsTableService() {
        service.upsertIntoTable("tx6", "query", "prompt", null, null, ANSWER_GENERATED, null, null, null);
        verify(tableService).upsertIntoTable(any(TableEntity.class));
    }

    // ---- IdempotencyStatusStore implementation ----

    @Test
    @DisplayName("Fenced terminal write carries the full row and the claim etag")
    void fencedTerminalWriteCarriesFullRowAndClaimEtag() {
        service.upsertTerminalFenced(null, "tx7", "query", "prompt", "chunks.json", "answer",
                ANSWER_GENERATED, null, OffsetDateTime.now(), 42L, "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"claimed\""));
        final TableEntity entity = captor.getValue();
        assertEquals("tx7", entity.getPartitionKey());
        assertEquals("tx7", entity.getRowKey());
        assertEquals("ANSWER_GENERATED", entity.getProperty(TC_ANSWER_STATUS));
        assertEquals("answer", entity.getProperty(TC_LLM_RESPONSE));
    }

    @Test
    @DisplayName("readForClaim maps status, etag and lease columns into a LeaseSnapshot")
    void readForClaimMapsSnapshot() throws EntityRetrievalException {
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:00:00Z");
        final TableEntity entity = mock(TableEntity.class);
        when(entity.getProperty(TC_ANSWER_STATUS)).thenReturn("ANSWER_GENERATION_PENDING");
        when(entity.getProperty(TC_LEASE_EXPIRES_AT)).thenReturn(expiry);
        when(entity.getProperty(TC_LEASE_OWNER)).thenReturn("owner-1");
        when(entity.getETag()).thenReturn("W/\"read\"");
        when(tableService.getFirstDocumentMatching("tx8", "tx8")).thenReturn(entity);

        final LeaseSnapshot snapshot = service.readForClaim(null, "tx8");

        assertEquals(new LeaseSnapshot("ANSWER_GENERATION_PENDING", "W/\"read\"", expiry, "owner-1"), snapshot);
    }

    @Test
    @DisplayName("readForClaim returns null when the row is missing")
    void readForClaimReturnsNullWhenRowMissing() throws EntityRetrievalException {
        when(tableService.getFirstDocumentMatching("tx9", "tx9")).thenReturn(null);
        assertNull(service.readForClaim(null, "tx9"));
    }

    @Test
    @DisplayName("isTerminal classifies GENERATED and FAILED as terminal, PENDING as not")
    void isTerminalClassifiesStatuses() {
        assertTrue(service.isTerminal("ANSWER_GENERATED"));
        assertTrue(service.isTerminal("ANSWER_GENERATION_FAILED"));
        assertFalse(service.isTerminal("ANSWER_GENERATION_PENDING"));
        assertFalse(service.isTerminal(null));
    }

    @Test
    @DisplayName("claimLease conditionally writes only the lease columns and returns the new etag")
    void claimLeaseWritesOnlyLeaseColumns() {
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:10:00Z");
        when(tableService.updateEntityIfUnchanged(any(TableEntity.class), eq("W/\"read\""))).thenReturn("W/\"claimed\"");

        final String newEtag = service.claimLease(null, "tx10", "W/\"read\"", "owner-1", expiry);

        assertEquals("W/\"claimed\"", newEtag);
        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"read\""));
        final TableEntity entity = captor.getValue();
        assertEquals("owner-1", entity.getProperty(TC_LEASE_OWNER));
        assertEquals(expiry, entity.getProperty(TC_LEASE_EXPIRES_AT));
        // lease claim must not touch the status column (MERGE keeps the rest)
        assertNull(entity.getProperties().get(TC_ANSWER_STATUS));
    }

    @Test
    @DisplayName("createClaimedRow inserts a minimal PENDING row with the lease applied")
    void createClaimedRowInsertsMinimalPendingRow() throws DuplicateRecordException {
        final OffsetDateTime expiry = OffsetDateTime.parse("2026-07-14T12:10:00Z");
        when(tableService.insertReturningEtag(any(TableEntity.class))).thenReturn("W/\"created\"");

        final String etag = service.createClaimedRow(null, "tx11", "owner-1", expiry);

        assertEquals("W/\"created\"", etag);
        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).insertReturningEtag(captor.capture());
        final TableEntity entity = captor.getValue();
        assertEquals("ANSWER_GENERATION_PENDING", entity.getProperty(TC_ANSWER_STATUS));
        assertEquals("owner-1", entity.getProperty(TC_LEASE_OWNER));
        assertEquals(expiry, entity.getProperty(TC_LEASE_EXPIRES_AT));
    }

    @Test
    @DisplayName("releaseLease marks the lease reclaimable via the epoch sentinel")
    void releaseLeaseMarksLeaseReclaimable() {
        service.releaseLease(null, "tx12", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"claimed\""));
        assertEquals(IdempotencyStatusStore.LEASE_RELEASED, captor.getValue().getProperty(TC_LEASE_EXPIRES_AT));
    }

    @Test
    @DisplayName("releaseLease is best-effort: a rejected conditional write is swallowed")
    void releaseLeaseSwallowsEtagMismatch() {
        doThrow(new EtagMismatchException("etag changed"))
                .when(tableService).updateEntityIfUnchanged(any(TableEntity.class), any());

        assertDoesNotThrow(() -> service.releaseLease(null, "tx13", "W/\"stale\""));

        verify(tableService).updateEntityIfUnchanged(any(TableEntity.class), eq("W/\"stale\""));
    }

    // ---- Client-aware partition keying ----

    private static final String CLIENT_A = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_B = "22222222-2222-2222-2222-222222222222";

    @Test
    @DisplayName("saveAnswerGenerationRequest writes the row under the clientId partition")
    void saveRequestPartitionsByClientId() throws DuplicateRecordException {
        service.saveAnswerGenerationRequest(CLIENT_A, "tx", "query", "prompt", ANSWER_GENERATED);

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).insertIntoTable(captor.capture());
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
        assertEquals("tx", captor.getValue().getRowKey());
    }

    @Test
    @DisplayName("Two clients sharing one transactionId are stored under distinct partition keys")
    void twoClientsSharingTransactionIdCoexistUnderDistinctPartitions() throws DuplicateRecordException {
        service.saveAnswerGenerationRequest(CLIENT_A, "shared-tx", "q", "p", ANSWER_GENERATED);
        service.saveAnswerGenerationRequest(CLIENT_B, "shared-tx", "q", "p", ANSWER_GENERATED);

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService, org.mockito.Mockito.times(2)).insertIntoTable(captor.capture());
        final List<TableEntity> entities = captor.getAllValues();
        assertEquals(CLIENT_A, entities.get(0).getPartitionKey());
        assertEquals(CLIENT_B, entities.get(1).getPartitionKey());
        assertEquals(entities.get(0).getRowKey(), entities.get(1).getRowKey());
    }

    @Test
    @DisplayName("getGeneratedAnswer scopes the lookup to the supplied client partition")
    void getGeneratedAnswerScopesToClientPartition() throws EntityRetrievalException {
        service.getGeneratedAnswer(CLIENT_A, "tx");

        verify(tableService).getFirstDocumentMatching(CLIENT_A, "tx");
    }

    @Test
    @DisplayName("readForClaim scopes the lookup to the supplied client partition")
    void readForClaimScopesToClientPartition() throws EntityRetrievalException {
        service.readForClaim(CLIENT_A, "tx");

        verify(tableService).getFirstDocumentMatching(CLIENT_A, "tx");
    }

    @Test
    @DisplayName("upsertTerminalFenced writes the fenced terminal row into the supplied client partition")
    void upsertTerminalFencedPartitionsByClientId() {
        service.upsertTerminalFenced(CLIENT_A, "tx", "query", "prompt", "chunks.json", "answer",
                ANSWER_GENERATED, null, OffsetDateTime.now(), 42L, "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"claimed\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("recordGroundednessScore writes the score into the supplied client partition")
    void recordGroundednessScorePartitionsByClientId() {
        service.recordGroundednessScore(CLIENT_A, "tx", new BigDecimal("0.87"));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).upsertIntoTable(captor.capture());
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("claimLease writes the lease into the supplied client partition")
    void claimLeasePartitionsByClientId() {
        service.claimLease(CLIENT_A, "tx", "W/\"read\"", "owner-1", OffsetDateTime.parse("2026-07-14T12:10:00Z"));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"read\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("createClaimedRow writes the defensive row into the supplied client partition")
    void createClaimedRowPartitionsByClientId() throws DuplicateRecordException {
        service.createClaimedRow(CLIENT_A, "tx", "owner-1", OffsetDateTime.parse("2026-07-14T12:10:00Z"));

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).insertReturningEtag(captor.capture());
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("releaseLease marks the reclaimable sentinel in the supplied client partition")
    void releaseLeasePartitionsByClientId() {
        service.releaseLease(CLIENT_A, "tx", "W/\"claimed\"");

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).updateEntityIfUnchanged(captor.capture(), eq("W/\"claimed\""));
        assertEquals(CLIENT_A, captor.getValue().getPartitionKey());
    }

    @Test
    @DisplayName("A blank clientId falls back to the row key as the partition key")
    void blankClientIdFallsBackToRowKeyPartition() throws DuplicateRecordException {
        service.saveAnswerGenerationRequest("", "tx", "query", "prompt", ANSWER_GENERATED);

        final ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableService).insertIntoTable(captor.capture());
        assertEquals("tx", captor.getValue().getPartitionKey());
    }
}

