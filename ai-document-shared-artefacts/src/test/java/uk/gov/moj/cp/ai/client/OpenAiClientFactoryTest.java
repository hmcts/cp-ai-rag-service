package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;

class OpenAiClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String DIFFERENT_ENDPOINT = "https://different-endpoint.com";

    @Test
    void getInstanceCreatesNewClientWhenNotInCache() {
        final OpenAIClient client = OpenAiClientFactory.getInstance(ENDPOINT);
        assertNotNull(client);
    }

    @Test
    void getInstanceReturnsCachedClientForSameEndpoint() {
        final OpenAIClient firstClient = OpenAiClientFactory.getInstance(ENDPOINT);
        final OpenAIClient secondClient = OpenAiClientFactory.getInstance(ENDPOINT);
        assertSame(firstClient, secondClient);
    }

    @Test
    void getInstanceReturnsNewClientForDifferentEndpoints() {
        final OpenAIClient firstClient = OpenAiClientFactory.getInstance(ENDPOINT);
        final OpenAIClient secondClient = OpenAiClientFactory.getInstance(DIFFERENT_ENDPOINT);
        assertNotSame(firstClient, secondClient);
    }

    @Test
    void getInstanceThrowsExceptionForNullEndpoint() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> OpenAiClientFactory.getInstance(null));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }

    @Test
    void getInstanceThrowsExceptionForEmptyEndpoint() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> OpenAiClientFactory.getInstance(""));
        assertEquals("Endpoint environment variable must be set.", exception.getMessage());
    }
}
