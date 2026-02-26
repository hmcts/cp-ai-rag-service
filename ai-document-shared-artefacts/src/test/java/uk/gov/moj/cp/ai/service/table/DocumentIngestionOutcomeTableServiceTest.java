package uk.gov.moj.cp.ai.service.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_METADATA;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_DOCUMENT_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateKeyForRowAndPartition;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

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
    @DisplayName("Successfully inserts document outcome")
    void successfullyInsertsDocumentOutcome() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insertIntoTable("docName", "docId", "status", "reason");

        verify(mockTableService).insertIntoTable(any(TableEntity.class));
    }

    @Test
    @DisplayName("Successfully inserts document outcome with documentId as partitionKey/rowKey")
    void successfullyInsertsDocumentOutcomeWithDocumentIdAsKey() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert("docId", "docName", "metadata","status", "reason");

        final ArgumentCaptor<TableEntity> tableEntityCaptor = ArgumentCaptor.forClass(TableEntity.class);
        verify(mockTableService).insertIntoTable(tableEntityCaptor.capture());

        final TableEntity actualTableEntity = tableEntityCaptor.getValue();
        assertThat(actualTableEntity.getPartitionKey()).isEqualTo("docId");
        assertThat(actualTableEntity.getRowKey()).isEqualTo("docId");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_ID)).isEqualTo("docId");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_FILE_NAME)).isEqualTo("docName");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_METADATA)).isEqualTo("metadata");
        assertThat(actualTableEntity.getProperty(TC_DOCUMENT_STATUS)).isEqualTo("status");
        assertThat(actualTableEntity.getProperty(TC_REASON)).isEqualTo("reason");
    }

    @Test
    @DisplayName("Successfully get document outcome by documentId")
    void successfullyGetDocumentOutcomeByDocumentId() throws DuplicateRecordException, EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        final TableEntity mockTableEntity = mock(TableEntity.class);
        when(mockTableService.getFirstDocumentMatching("docId", "docId")).thenReturn(mockTableEntity);

        final DocumentIngestionOutcome document = service.getDocumentById("docId");

        verify(mockTableService).getFirstDocumentMatching("docId", "docId");
    }

    @Test
    @DisplayName("Successfully upserts document outcome")
    void successfullyUpsertsDocumentOutcome() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.upsertIntoTable("docName", "docId", "status", "reason");

        verify(mockTableService).upsertIntoTable(any(TableEntity.class));
    }

    @Test
    @DisplayName("Throws exception when insert fails due to duplicate record")
    void throwsExceptionWhenInsertFailsDueToDuplicateRecord() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        doThrow(new DuplicateRecordException("Upsert failed")).when(mockTableService).insertIntoTable(any(TableEntity.class));
        ;

        assertThrows(DuplicateRecordException.class,
                () -> service.insertIntoTable("docName", "docId", "status", "reason"));

    }

    @Test
    @DisplayName("Logs and throws runtime exception when upsert fails")
    void logsAndThrowsRuntimeExceptionWhenUpsertFails() {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);
        doThrow(new RuntimeException("Upsert failed")).when(mockTableService).upsertIntoTable(any(TableEntity.class));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.upsertIntoTable("docName", "docId", "status", "reason"));

        assertEquals("Failed to UPSERT record for document 'docName' with ID: 'docId", exception.getMessage());
    }

    @Test
    @DisplayName("Returns document outcome when matching entity is found")
    void returnsDocumentOutcomeWhenMatchingEntityIsFound() throws EntityRetrievalException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        final String docName = "docName";
        final TableEntity entity = new TableEntity("partitionKey", "rowKey")
                .addProperty("DocumentId", "docId")
                .addProperty("DocumentFileName", docName)
                .addProperty("DocumentStatus", "status")
                .addProperty("Reason", "reason");
        when(mockTableService.getFirstDocumentMatching(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName))).thenReturn(entity);

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching(docName);

        assertNotNull(outcome);
        assertEquals("docId", outcome.getDocumentId());
        assertEquals(docName, outcome.getDocumentName());
        assertEquals("status", outcome.getStatus());
        assertEquals("reason", outcome.getReason());
    }

    @Test
    @DisplayName("Returns null when no matching entity is found")
    void returnsNullWhenNoMatchingEntityIsFound() throws EntityRetrievalException {
        final String docName = "docName";
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        when(mockTableService.getFirstDocumentMatching(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName)))
                .thenReturn(null);

        final DocumentIngestionOutcome outcome = service.getFirstDocumentMatching(docName);

        assertNull(outcome);
    }

    @Test
    @DisplayName("Cascades EntityRetrievalException when error fetching matching document")
    void throwsExceptionWhenTableServiceExceptionThrownWithDifferentCode() throws EntityRetrievalException {
        final String docName = "docName";
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        when(mockTableService.getFirstDocumentMatching(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName)))
                .thenThrow(new EntityRetrievalException("Entity not found"));

        assertThrows(EntityRetrievalException.class, () -> service.getFirstDocumentMatching(docName));

    }

    @Test
    @DisplayName("Handles null properties gracefully when mapping entity to DocumentIngestionOutcome")
    void handlesNullPropertiesGracefullyWhenMappingEntityToDocumentIngestionOutcome() throws EntityRetrievalException {
        final String docName = "docName";
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        final TableEntity entity = new TableEntity("partitionKey", "rowKey");
        when(mockTableService.getFirstDocumentMatching(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName))).thenReturn(entity);

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching(docName);

        assertNotNull(outcome);
        assertNull(outcome.getDocumentId());
        assertNull(outcome.getDocumentName());
        assertNull(outcome.getStatus());
        assertNull(outcome.getReason());

        verify(mockTableService).getFirstDocumentMatching(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName));
    }
}
