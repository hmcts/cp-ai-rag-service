package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.search.documents.SearchClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AISearchClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String INDEX_NAME = "example-index";

    @Test
    void getInstanceCreatesNewClientWhenNotInCache() {
        SearchClient client = AISearchClientFactory.getInstance(ENDPOINT, INDEX_NAME);
        assertNotNull(client);
    }

    @Test
    void getInstanceReturnsCachedClientForSameEndpointAndIndexName() {
        SearchClient firstClient = AISearchClientFactory.getInstance(ENDPOINT, INDEX_NAME);
        SearchClient secondClient = AISearchClientFactory.getInstance(ENDPOINT, INDEX_NAME);
        assertSame(firstClient, secondClient);
    }

    @Test
    void getInstanceReturnsNewClientForDifferentEndpointAndIndexName() {
        SearchClient firstClient = AISearchClientFactory.getInstance(ENDPOINT, INDEX_NAME);
        SearchClient secondClient = AISearchClientFactory.getInstance(ENDPOINT + "different", INDEX_NAME);
        SearchClient thirdClient = AISearchClientFactory.getInstance(ENDPOINT, INDEX_NAME + "different");
        assertNotSame(firstClient, secondClient);
        assertNotSame(firstClient, thirdClient);
        assertNotSame(secondClient, thirdClient);
    }

    @Test
    void getInstanceThrowsExceptionForNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AISearchClientFactory.getInstance(null, INDEX_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AISearchClientFactory.getInstance("", INDEX_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForNullIndexName() {
        assertThrows(IllegalArgumentException.class, () -> AISearchClientFactory.getInstance(ENDPOINT, null));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyIndexName() {
        assertThrows(IllegalArgumentException.class, () -> AISearchClientFactory.getInstance(ENDPOINT, ""));
    }
}
