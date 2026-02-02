package uk.gov.moj.cp.ai.service.table;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_ANSWER_STATUS;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_CHUNKED_ENTRIES_FILE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_LLM_RESPONSE;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_QUERY_PROMPT;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_DURATION;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_RESPONSE_GENERATION_TIME;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_TRANSACTION_ID;
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_USER_QUERY;
import static uk.gov.moj.cp.ai.service.table.AnswerGenerationStatus.ANSWER_GENERATED;

import uk.gov.moj.cp.ai.entity.GeneratedAnswer;
import uk.gov.moj.cp.ai.exception.DuplicateRecordException;
import uk.gov.moj.cp.ai.exception.EntityRetrievalException;

import com.azure.data.tables.models.TableEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        service.saveAnswerGenerationRequest("tx1", "query", "prompt", ANSWER_GENERATED);
        verify(tableService).insertIntoTable(any(TableEntity.class));
    }

    @Test
    @DisplayName("Throws exception when save fails due to duplicate record")
    void throwsExceptionWhenSaveFailsDueToDuplicateRecord() throws DuplicateRecordException {
        doThrow(new DuplicateRecordException("Insert failed")).when(tableService).insertIntoTable(any(TableEntity.class));
        assertThrows(DuplicateRecordException.class, () ->
                service.saveAnswerGenerationRequest("tx2", "query", "prompt", ANSWER_GENERATED));
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

        GeneratedAnswer answer = service.getGeneratedAnswer(transactionId);
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
        assertNull(service.getGeneratedAnswer("tx4"));
    }

    @Test
    @DisplayName("Handles null properties gracefully when mapping entity to GeneratedAnswer")
    void handlesNullPropertiesGracefullyWhenMappingEntityToGeneratedAnswer() throws EntityRetrievalException {
        TableEntity entity = new TableEntity("tx5", "tx5");
        when(tableService.getFirstDocumentMatching("tx5", "tx5")).thenReturn(entity);
        GeneratedAnswer answer = service.getGeneratedAnswer("tx5");
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
}

