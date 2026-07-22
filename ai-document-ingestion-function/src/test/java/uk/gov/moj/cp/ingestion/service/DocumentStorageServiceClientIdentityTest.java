package uk.gov.moj.cp.ingestion.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.index.IndexConstants.CLIENT_ID;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.List;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A chunk's clientId is written to the search document only when the chunk carries one. Writing the
 * field unconditionally would break retrieval against a live index that does not yet define it, so
 * the column is emitted conditionally — mirroring the conditional client-scoped select on the read side.
 */
class DocumentStorageServiceClientIdentityTest {

    private static final String CLIENT_ID_VALUE = "11111111-1111-1111-1111-111111111111";
    private static final int VECTOR_DIMENSIONS = 3072;

    private SearchClient searchClient;
    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        searchClient = org.mockito.Mockito.mock(SearchClient.class);
        when(searchClient.getIndexName()).thenReturn("test-index");
        documentStorageService = new DocumentStorageService(searchClient);
    }

    @Test
    @DisplayName("writes the client id column when the chunk carries a client id")
    void shouldWriteClientIdColumn_whenChunkHasClientId() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(CLIENT_ID_VALUE)));

        final SearchDocument uploaded = captureUploaded();
        assertThat(uploaded.containsKey(CLIENT_ID), is(true));
        assertThat(uploaded.get(CLIENT_ID), is(CLIENT_ID_VALUE));
    }

    @Test
    @DisplayName("omits the client id column when the chunk has no client id")
    void shouldOmitClientIdColumn_whenChunkHasNoClientId() throws Exception {
        documentStorageService.uploadChunks(List.of(chunk(null)));

        final SearchDocument uploaded = captureUploaded();
        assertThat(uploaded.containsKey(CLIENT_ID), is(false));
    }

    @SuppressWarnings("unchecked")
    private SearchDocument captureUploaded() {
        final ArgumentCaptor<List<SearchDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchClient).uploadDocuments(captor.capture());
        return captor.getValue().get(0);
    }

    private ChunkedEntry chunk(final String clientId) {
        return ChunkedEntry.builder()
                .id("1")
                .chunk("content")
                .chunkVector(Collections.nCopies(VECTOR_DIMENSIONS, 0.1f))
                .documentFileName("doc.pdf")
                .pageNumber(1)
                .documentId("doc1")
                .chunkIndex(0)
                .clientId(clientId)
                .build();
    }
}
