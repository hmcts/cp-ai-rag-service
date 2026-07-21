package uk.gov.moj.cp.ai.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MTDI-02 (AC-010) spec: {@code vector-db-index-schema-v2.json} declares a top-level {@code clientId}
 * field with exactly the v2 attribute shape mirroring {@code documentId}
 * ({@code filterable: true, searchable: false, retrievable: true, stored: true, sortable: false, facetable: false}).
 * Schema JSON is data — the field is added so this passes.
 */
class VectorDbIndexSchemaV2Test {

    private static final String SCHEMA_RESOURCE = "/vector-db-index-schema-v2.json";

    @Test
    @DisplayName("AC-010: clientId field is declared with the expected v2 attribute shape")
    void shouldDeclareClientIdFieldWithExpectedAttributes() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode schema;
        try (InputStream in = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            assertNotNull(in, "schema resource not found on classpath: " + SCHEMA_RESOURCE);
            schema = mapper.readTree(in);
        }

        JsonNode clientIdField = null;
        for (final JsonNode field : schema.get("fields")) {
            if ("clientId".equals(field.path("name").asText())) {
                clientIdField = field;
                break;
            }
        }

        assertNotNull(clientIdField, "clientId field not declared in schema");
        assertEquals("Edm.String", clientIdField.path("type").asText());
        assertTrue(clientIdField.path("filterable").asBoolean(), "filterable must be true");
        assertEquals(false, clientIdField.path("searchable").asBoolean(), "searchable must be false");
        assertTrue(clientIdField.path("retrievable").asBoolean(), "retrievable must be true");
        assertTrue(clientIdField.path("stored").asBoolean(), "stored must be true");
        assertEquals(false, clientIdField.path("sortable").asBoolean(), "sortable must be false");
        assertEquals(false, clientIdField.path("facetable").asBoolean(), "facetable must be false");
    }
}
