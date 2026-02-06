package uk.gov.moj.cp.ai.service.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class TableServiceTest {
    private TableClient tableClient;
    private TableService tableService;

    @BeforeEach
    void setUp() {
        tableClient = mock(TableClient.class);
        tableService = new TableService(tableClient);
    }

    @Test
    @DisplayName("Throws exception when table name is null or empty")
    void throwsExceptionWhenTableNameIsNullOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new TableService((String) null));
        assertThrows(IllegalArgumentException.class, () -> new TableService(""));
    }

    @Test
    @DisplayName("Successfully inserts entity into table")
    void successfullyInsertsEntityIntoTable() throws DuplicateRecordException {
        TableEntity entity = new TableEntity("partition", "row");
        tableService.insertIntoTable(entity);
        verify(tableClient).createEntity(entity);
    }

    @Test
    @DisplayName("Throws DuplicateRecordException when entity already exists")
    void throwsDuplicateRecordExceptionWhenEntityAlreadyExists() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.ENTITY_ALREADY_EXISTS);
        doThrow(tse).when(tableClient).createEntity(entity);
        assertThrows(DuplicateRecordException.class, () -> tableService.insertIntoTable(entity));
    }

    @Test
    @DisplayName("Throws RuntimeException for other TableServiceException on insert")
    void throwsRuntimeExceptionForOtherTableServiceExceptionOnInsert() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.INVALID_INPUT);
        doThrow(tse).when(tableClient).createEntity(entity);
        assertThrows(RuntimeException.class, () -> tableService.insertIntoTable(entity));
    }

    @Test
    @DisplayName("Throws RuntimeException for generic Exception on insert")
    void throwsRuntimeExceptionForGenericExceptionOnInsert() {
        TableEntity entity = new TableEntity("partition", "row");
        doThrow(new RuntimeException("fail")).when(tableClient).createEntity(entity);
        assertThrows(RuntimeException.class, () -> tableService.insertIntoTable(entity));
    }

    @Test
    @DisplayName("Successfully upserts entity into table")
    void successfullyUpsertsEntityIntoTable() {
        TableEntity entity = new TableEntity("partition", "row");
        tableService.upsertIntoTable(entity);
        verify(tableClient).upsertEntity(entity);
    }

    @Test
    @DisplayName("Throws RuntimeException for Exception on upsert")
    void throwsRuntimeExceptionForExceptionOnUpsert() {
        TableEntity entity = new TableEntity("partition", "row");
        doThrow(new RuntimeException("fail")).when(tableClient).upsertEntity(entity);
        assertThrows(RuntimeException.class, () -> tableService.upsertIntoTable(entity));
    }

    @Test
    @DisplayName("Returns entity when found by partition and row key")
    void returnsEntityWhenFoundByPartitionAndRowKey() throws EntityRetrievalException {
        TableEntity entity = new TableEntity("partition", "row");
        when(tableClient.getEntity("partition", "row")).thenReturn(entity);
        TableEntity result = tableService.getFirstDocumentMatching("partition", "row");
        assertEquals(entity, result);
    }

    @Test
    @DisplayName("Returns null when entity not found (ENTITY_NOT_FOUND)")
    void returnsNullWhenEntityNotFoundEntityNotFound() throws EntityRetrievalException {
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.ENTITY_NOT_FOUND);
        when(tableClient.getEntity("partition", "row")).thenThrow(tse);
        assertNull(tableService.getFirstDocumentMatching("partition", "row"));
    }

    @Test
    @DisplayName("Returns null when entity not found (RESOURCE_NOT_FOUND)")
    void returnsNullWhenEntityNotFoundResourceNotFound() throws EntityRetrievalException {
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.RESOURCE_NOT_FOUND);
        when(tableClient.getEntity("partition", "row")).thenThrow(tse);
        assertNull(tableService.getFirstDocumentMatching("partition", "row"));
    }

    @Test
    @DisplayName("Throws EntityRetrievalException for other TableServiceException on get")
    void throwsEntityRetrievalExceptionForOtherTableServiceExceptionOnGet() {
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.OPERATION_TIMED_OUT);
        when(tableClient.getEntity("partition", "row")).thenThrow(tse);
        assertThrows(EntityRetrievalException.class, () -> tableService.getFirstDocumentMatching("partition", "row"));
    }

    @Test
    @DisplayName("Throws EntityRetrievalException for generic Exception on get")
    void throwsEntityRetrievalExceptionForGenericExceptionOnGet() {
        when(tableClient.getEntity("partition", "row")).thenThrow(new RuntimeException("fail"));
        assertThrows(EntityRetrievalException.class, () -> tableService.getFirstDocumentMatching("partition", "row"));
    }

    @Test
    @DisplayName("TableClientFactory is used in string constructor")
    void tableClientFactoryIsUsedInStringConstructor() {
        try (MockedStatic<TableClientFactory> factory = Mockito.mockStatic(TableClientFactory.class)) {
            TableClient mockClient = mock(TableClient.class);
            factory.when(() -> TableClientFactory.getInstance("tableName")).thenReturn(mockClient);
            TableService service = new TableService("tableName");
            assertNotNull(service);
        }
    }
}

