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
import static uk.gov.moj.cp.ai.entity.StorageTableColumns.TC_REASON;

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
    @DisplayName("Successfully inserts document outcome with documentId as partitionKey/rowKey")
    void successfullyInsertsDocumentOutcomeWithDocumentIdAsKey() throws DuplicateRecordException {
        final DocumentIngestionOutcomeTableService service = new DocumentIngestionOutcomeTableService(mockTableService);

        service.insert("docId", "docName", "metadata","doc1,doc2","status", "reason");

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

        final DocumentIngestionOutcome document = service.getDocumentById("docId");

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

        service.upsertDocument(docId, "status", "reason");

        verify(mockTableService).upsertIntoTable(any(TableEntity.class));
    }
}
