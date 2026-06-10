package uk.gov.moj.cp.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchIndexAdminTest {

    private static final String V2_SCHEMA = "/vector-db-index-schema-v2.json";

    @Test
    void loadSchemaWithNameOverridesTheIndexNameAndPreservesEverythingElse() throws Exception {
        final JsonNode root = new ObjectMapper()
                .readTree(SearchIndexAdmin.loadSchemaWithName(V2_SCHEMA, "my-target-index"));

        assertThat(root.get("name").asText()).isEqualTo("my-target-index"); // name overridden
        assertThat(root.has("fields")).isTrue();                            // rest of the schema preserved
        assertThat(root.has("vectorSearch")).isTrue();
        assertThat(root.toString()).contains("chunkVector");
    }

    @Test
    void loadSchemaWithNameThrowsWhenTheResourceIsMissing() {
        assertThatThrownBy(() -> SearchIndexAdmin.loadSchemaWithName("/does-not-exist.json", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void createTargetIndexParsesTheSchemaWithTheOverriddenNameAndCreatesIt() throws Exception {
        final SearchIndexClient indexClient = mock(SearchIndexClient.class);

        SearchIndexAdmin.createTargetIndex(indexClient, "ai-rag-service-index-test", V2_SCHEMA);

        final ArgumentCaptor<SearchIndex> created = ArgumentCaptor.forClass(SearchIndex.class);
        verify(indexClient).createOrUpdateIndex(created.capture());
        // Confirms the real v2 schema parses via SearchIndex.fromJson and that the physical name is overridden.
        assertThat(created.getValue().getName()).isEqualTo("ai-rag-service-index-test");
        assertThat(created.getValue().getFields()).isNotEmpty();
    }

    @Test
    void verifyCountsPassesWhenSourceAndTargetMatch() {
        final SearchIndexClient indexClient = indexClientWithCounts(100L, 100L);
        SearchIndexAdmin.verifyCounts(indexClient, "source", "target", 100L); // no exception
    }

    @Test
    void verifyCountsThrowsOnMismatch() {
        final SearchIndexClient indexClient = indexClientWithCounts(100L, 99L);

        assertThatThrownBy(() -> SearchIndexAdmin.verifyCounts(indexClient, "source", "target", 99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source=100")
                .hasMessageContaining("target=99");
    }

    private static SearchIndexClient indexClientWithCounts(final long sourceCount, final long targetCount) {
        final SearchIndexClient indexClient = mock(SearchIndexClient.class);
        final SearchClient source = mock(SearchClient.class);
        final SearchClient target = mock(SearchClient.class);
        when(indexClient.getSearchClient("source")).thenReturn(source);
        when(indexClient.getSearchClient("target")).thenReturn(target);
        when(source.getDocumentCount()).thenReturn(sourceCount);
        when(target.getDocumentCount()).thenReturn(targetCount);
        return indexClient;
    }
}
