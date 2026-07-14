package uk.gov.moj.cp.ai.service.table;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.Response;
import com.azure.core.util.Context;
import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.data.tables.models.TableErrorCode;
import com.azure.data.tables.models.TableServiceError;
import com.azure.data.tables.models.TableServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import uk.gov.moj.cp.ai.client.TableClientFactory;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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

    @SuppressWarnings("unchecked")
    private Response<Void> responseWithEtag(final String etag) {
        Response<Void> response = mock(Response.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders().set(HttpHeaderName.ETAG, etag));
        return response;
    }

    @Test
    @DisplayName("Conditional update MERGEs with If-Match, injects the etag on the entity, and returns the new etag")
    void conditionalUpdateMergesWithIfMatchAndReturnsNewEtag() {
        TableEntity entity = new TableEntity("partition", "row");
        Response<Void> response = responseWithEtag("W/\"new\"");
        when(tableClient.updateEntityWithResponse(eq(entity), eq(TableEntityUpdateMode.MERGE), eq(true), isNull(), eq(Context.NONE)))
                .thenReturn(response);

        String newEtag = tableService.updateEntityIfUnchanged(entity, "W/\"old\"");

        assertEquals("W/\"new\"", newEtag);
        // the SDK reads If-Match from the entity's odata.etag property
        assertEquals("W/\"old\"", entity.getProperties().get("odata.etag"));
    }

    @Test
    @DisplayName("Restores the closing quote azure-core strips from the ETag response header")
    void restoresClosingQuoteStrippedFromEtagResponseHeader() {
        // Observed against real Table Storage: the header arrives as W/"datetime'...' (unbalanced);
        // sending that back as If-Match fails with HTTP 400 InvalidInput.
        TableEntity entity = new TableEntity("partition", "row");
        Response<Void> response = responseWithEtag("W/\"datetime'2026-07-14T19%3A08%3A04.616648Z'");
        when(tableClient.updateEntityWithResponse(eq(entity), eq(TableEntityUpdateMode.MERGE), eq(true), isNull(), eq(Context.NONE)))
                .thenReturn(response);

        assertEquals("W/\"datetime'2026-07-14T19%3A08%3A04.616648Z'\"",
                tableService.updateEntityIfUnchanged(entity, "W/\"old\""));
    }

    @Test
    @DisplayName("Throws EtagMismatchException when the conditional update is rejected (412)")
    void throwsEtagMismatchExceptionWhenConditionalUpdateRejected() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.UPDATE_CONDITION_NOT_SATISFIED);
        doThrow(tse).when(tableClient).updateEntityWithResponse(any(), any(), anyBoolean(), any(), any());

        assertThrows(EtagMismatchException.class, () -> tableService.updateEntityIfUnchanged(entity, "W/\"old\""));
    }

    @Test
    @DisplayName("Throws RuntimeException for other TableServiceException on conditional update")
    void throwsRuntimeExceptionForOtherTableServiceExceptionOnConditionalUpdate() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.OPERATION_TIMED_OUT);
        HttpResponse httpResponse = mock(HttpResponse.class);
        when(httpResponse.getStatusCode()).thenReturn(500);
        when(tse.getResponse()).thenReturn(httpResponse);
        doThrow(tse).when(tableClient).updateEntityWithResponse(any(), any(), anyBoolean(), any(), any());

        assertThrows(RuntimeException.class, () -> tableService.updateEntityIfUnchanged(entity, "W/\"old\""));
    }

    @Test
    @DisplayName("Throws RuntimeException for generic Exception on conditional update")
    void throwsRuntimeExceptionForGenericExceptionOnConditionalUpdate() {
        TableEntity entity = new TableEntity("partition", "row");
        doThrow(new RuntimeException("fail")).when(tableClient).updateEntityWithResponse(any(), any(), anyBoolean(), any(), any());

        assertThrows(RuntimeException.class, () -> tableService.updateEntityIfUnchanged(entity, "W/\"old\""));
    }

    @Test
    @DisplayName("Insert returning etag creates the entity and returns the created row's etag")
    void insertReturningEtagCreatesEntityAndReturnsEtag() throws DuplicateRecordException {
        TableEntity entity = new TableEntity("partition", "row");
        Response<Void> response = responseWithEtag("W/\"created\"");
        when(tableClient.createEntityWithResponse(eq(entity), isNull(), eq(Context.NONE)))
                .thenReturn(response);

        assertEquals("W/\"created\"", tableService.insertReturningEtag(entity));
    }

    @Test
    @DisplayName("Insert returning etag throws DuplicateRecordException when entity already exists")
    void insertReturningEtagThrowsDuplicateRecordExceptionWhenEntityAlreadyExists() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.ENTITY_ALREADY_EXISTS);
        doThrow(tse).when(tableClient).createEntityWithResponse(any(), any(), any());

        assertThrows(DuplicateRecordException.class, () -> tableService.insertReturningEtag(entity));
    }

    @Test
    @DisplayName("Insert returning etag throws RuntimeException for other TableServiceException")
    void insertReturningEtagThrowsRuntimeExceptionForOtherTableServiceException() {
        TableEntity entity = new TableEntity("partition", "row");
        TableServiceException tse = mock(TableServiceException.class);
        when(tse.getValue()).thenReturn(mock(TableServiceError.class));
        when(tse.getValue().getErrorCode()).thenReturn(TableErrorCode.INVALID_INPUT);
        doThrow(tse).when(tableClient).createEntityWithResponse(any(), any(), any());

        assertThrows(RuntimeException.class, () -> tableService.insertReturningEtag(entity));
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

