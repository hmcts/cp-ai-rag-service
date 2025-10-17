package uk.gov.moj.cp.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.entity.DocumentIngestionOutcome;

import java.util.stream.Stream;

import com.azure.core.http.rest.PagedIterable;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TableStorageServiceTest {

    @Test
    @DisplayName("Throws exception when connection string or table name is null or empty")
    void throwsExceptionWhenConnectionStringOrTableNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService(null, "tableName"));
        assertEquals("Table connection string and name cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("", "tableName"));
        assertEquals("Table connection string and name cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("connectionString", null));
        assertEquals("Table connection string and name cannot be null or empty", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new TableStorageService("connectionString", ""));
        assertEquals("Table connection string and name cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Successfully upserts document outcome")
    void successfullyUpsertsDocumentOutcome() {
        TableClient tableClientMock = mock(TableClient.class);
        TableStorageService service = new TableStorageService(tableClientMock);

        service.upsertDocumentOutcome("docName", "docId", "status", "reason");

        verify(tableClientMock, times(1)).upsertEntity(any(TableEntity.class));
    }

    @Test
    @DisplayName("Logs and throws runtime exception when upsert fails")
    void logsAndThrowsRuntimeExceptionWhenUpsertFails() {
        TableClient tableClientMock = mock(TableClient.class);
        TableStorageService service = new TableStorageService(tableClientMock);
        doThrow(new RuntimeException("Upsert failed")).when(tableClientMock).upsertEntity(any(TableEntity.class));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.upsertDocumentOutcome("docName", "docId", "status", "reason"));

        assertEquals("Failed to upsert document outcome", exception.getMessage());
    }

    @Test
    @DisplayName("Returns document outcome when matching entity is found")
    void returnsDocumentOutcomeWhenMatchingEntityIsFound() {
        TableClient tableClientMock = mock(TableClient.class);
        TableStorageService service = new TableStorageService(tableClientMock);

        TableEntity entity = new TableEntity("partitionKey", "rowKey")
                .addProperty("DocumentId", "docId")
                .addProperty("DocumentFileName", "docName")
                .addProperty("DocumentStatus", "status")
                .addProperty("Reason", "reason");
        PagedIterable<TableEntity> mockPagedIterable = mock(PagedIterable.class);
        when(tableClientMock.listEntities(any(), any(), any())).thenReturn(mockPagedIterable);
        when(mockPagedIterable.stream()).thenReturn(Stream.of(entity));

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching("docName");

        assertNotNull(outcome);
        assertEquals("docId", outcome.getDocumentId());
        assertEquals("docName", outcome.getDocumentName());
        assertEquals("status", outcome.getStatus());
        assertEquals("reason", outcome.getReason());
    }

    @Test
    @DisplayName("Returns null when no matching entity is found")
    void returnsNullWhenNoMatchingEntityIsFound() {
        TableClient tableClientMock = mock(TableClient.class);
        TableStorageService service = new TableStorageService(tableClientMock);

        PagedIterable<TableEntity> mockPagedIterable = mock(PagedIterable.class);
        when(tableClientMock.listEntities(any(), any(), any())).thenReturn(mockPagedIterable);
        when(mockPagedIterable.stream()).thenReturn(Stream.empty());

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching("docName");

        assertNull(outcome);
    }

    @Test
    @DisplayName("Handles null properties gracefully when mapping entity to DocumentIngestionOutcome")
    void handlesNullPropertiesGracefullyWhenMappingEntityToDocumentIngestionOutcome() {
        TableClient tableClientMock = mock(TableClient.class);
        TableStorageService service = new TableStorageService(tableClientMock);

        TableEntity entity = new TableEntity("partitionKey", "rowKey");
        PagedIterable<TableEntity> mockPagedIterable = mock(PagedIterable.class);
        when(tableClientMock.listEntities(any(), any(), any())).thenReturn(mockPagedIterable);
        when(mockPagedIterable.stream()).thenReturn(Stream.of(entity));

        DocumentIngestionOutcome outcome = service.getFirstDocumentMatching("docName");

        assertNotNull(outcome);
        assertNull(outcome.getDocumentId());
        assertNull(outcome.getDocumentName());
        assertNull(outcome.getStatus());
        assertNull(outcome.getReason());
    }
}
