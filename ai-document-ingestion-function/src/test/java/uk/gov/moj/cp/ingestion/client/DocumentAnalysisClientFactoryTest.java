package uk.gov.moj.cp.ingestion.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentAnalysisClientFactoryTest {

    protected static final String ENDPOINT = "https://example.com";
    protected static final String DIFFERENT_ENDPOINT = "https://different-example.com";

    @Test
    @DisplayName("Throws exception when endpoint is null or empty")
    void getInstanceThrowsExceptionForNullOrEmptyEndpoint() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> DocumentAnalysisClientFactory.getInstance(null));
        assertEquals("Endpoint value must be set.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> DocumentAnalysisClientFactory.getInstance(""));
        assertEquals("Endpoint value must be set.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns cached client for the same endpoint")
    void getInstanceReturnsCachedClientForSameEndpoint() {
        DocumentAnalysisClient client1 = DocumentAnalysisClientFactory.getInstance(ENDPOINT);
        DocumentAnalysisClient client2 = DocumentAnalysisClientFactory.getInstance(ENDPOINT);

        assertNotNull(client1);
        assertNotNull(client2);
        assertSame(client1, client2);
    }

    @Test
    @DisplayName("Creates new client for different endpoints")
    void getInstanceCreatesNewClientForDifferentEndpoints() {

        DocumentAnalysisClient client1 = DocumentAnalysisClientFactory.getInstance(ENDPOINT);
        DocumentAnalysisClient client2 = DocumentAnalysisClientFactory.getInstance(DIFFERENT_ENDPOINT);

        assertNotNull(client1);
        assertNotNull(client2);
        assertNotSame(client1, client2);
    }

}
