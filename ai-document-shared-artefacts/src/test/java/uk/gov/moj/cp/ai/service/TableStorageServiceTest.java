package uk.gov.moj.cp.ai.service;

import static com.azure.data.tables.models.TableErrorCode.AUTHORIZATION_RESOURCE_TYPE_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.util.RowKeyUtil.generateKeyForRowAndPartition;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TableStorageServiceTest {

    @Test
    @DisplayName("Throws exception when connection string or table name is null or empty")
    void throwsExceptionWhenConnectionStringOrTableNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService(null, "tableName"));
        assertEquals("Table storage endpoint and table name cannot be null or empty.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("", "tableName"));
        assertEquals("Table storage endpoint and table name cannot be null or empty.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("endpoint", null));
        assertEquals("Table storage endpoint and table name cannot be null or empty.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("endpoint", ""));
        assertEquals("Table storage endpoint and table name cannot be null or empty.", exception.getMessage());
    }

    @Test
    @DisplayName("Successfully inserts document outcome")
    void successfullyInsertsDocumentOutcome() throws DuplicateRecordException {
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        service.insertIntoTable("docName", "docId", "status", "reason");

        verify(tableClientMock).createEntity(any(TableEntity.class));
    }

    @Test
    @DisplayName("Successfully upserts document outcome")
    void successfullyUpsertsDocumentOutcome() {
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        service.upsertIntoTable("docName", "docId", "status", "reason");

        verify(tableClientMock).upsertEntity(any(TableEntity.class));
    }

    @Test
    @DisplayName("Throws exception when insert fails due to duplicate record")
    void throwsExceptionWhenInsertFailsDueToDuplicateRecord() {
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);
        doThrow(new TableServiceException("Upsert failed", null, new TableServiceError("EntityAlreadyExists", "some error message"))).when(tableClientMock).createEntity(any(TableEntity.class));

        assertThrows(DuplicateRecordException.class,
                () -> service.insertIntoTable("docName", "docId", "status", "reason"));

    }

    @Test
    @DisplayName("Logs and throws runtime exception when upsert fails")
    void logsAndThrowsRuntimeExceptionWhenUpsertFails() {
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);
        doThrow(new RuntimeException("Upsert failed")).when(tableClientMock).upsertEntity(any(TableEntity.class));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.upsertIntoTable("docName", "docId", "status", "reason"));

        assertEquals("Failed to UPSERT record for document 'docName' with ID: 'docId", exception.getMessage());
    }

    @Test
    @DisplayName("Returns document outcome when matching entity is found")
    void returnsDocumentOutcomeWhenMatchingEntityIsFound() throws EntityRetrievalException {
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        final String docName = "docName";
        final TableEntity entity = new TableEntity("partitionKey", "rowKey")
                .addProperty("DocumentId", "docId")
                .addProperty("DocumentFileName", docName)
                .addProperty("DocumentStatus", "status")
                .addProperty("Reason", "reason");
        when(tableClientMock.getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName))).thenReturn(entity);

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
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        when(tableClientMock.getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName)))
                .thenThrow(new TableServiceException("Entity not found", null, new TableServiceError("EntityNotFound", "some error message")));

        final DocumentIngestionOutcome outcome = service.getFirstDocumentMatching(docName);

        assertNull(outcome);
    }

    @Test
    @DisplayName("Throws exception when another error code supplied with table service exception")
    void throwsExceptionWhenTableServiceExceptionThrownWithDifferentCode() {
        final String docName = "docName";
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        when(tableClientMock.getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName)))
                .thenThrow(new TableServiceException("Entity not found", null, new TableServiceError(AUTHORIZATION_RESOURCE_TYPE_MISMATCH.getValue(), "some error message")));

        assertThrows(EntityRetrievalException.class, () -> service.getFirstDocumentMatching(docName));

    }

    @Test
    @DisplayName("Throws exception when another error code supplied with table service exception")
    void throwsExceptionWhenAnyOtherExceptionThrown() {
        final String docName = "docName";
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        when(tableClientMock.getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName)))
                .thenThrow(new RuntimeException());

        assertThrows(EntityRetrievalException.class, () -> service.getFirstDocumentMatching(docName));

    }

    @Test
    @DisplayName("Handles null properties gracefully when mapping entity to DocumentIngestionOutcome")
    void handlesNullPropertiesGracefullyWhenMappingEntityToDocumentIngestionOutcome() throws EntityRetrievalException {
        final String docName = "docName";
        final TableClient tableClientMock = mock(TableClient.class);
        final TableStorageService service = new TableStorageService(tableClientMock);

        final TableEntity entity = new TableEntity("partitionKey", "rowKey");
        when(tableClientMock.getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName))).thenReturn(entity);

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching(docName);

        assertNotNull(outcome);
        assertNull(outcome.getDocumentId());
        assertNull(outcome.getDocumentName());
        assertNull(outcome.getStatus());
        assertNull(outcome.getReason());

        verify(tableClientMock).getEntity(generateKeyForRowAndPartition(docName), generateKeyForRowAndPartition(docName));
    }
}
