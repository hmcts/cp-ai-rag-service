package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.ai.openai.OpenAIClient;
import org.junit.jupiter.api.Test;

class AzureOpenAiClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String DIFFERENT_ENDPOINT = "https://different-endpoint.com";

    @Test
    void getInstanceCreatesNewClientWhenNotInCache() {
        OpenAIClient client = AzureOpenAiClientFactory.getInstance(ENDPOINT);
        assertNotNull(client);
    }

    @Test
    void getInstanceReturnsCachedClientForSameEndpoint() {
        OpenAIClient firstClient = AzureOpenAiClientFactory.getInstance(ENDPOINT);
        OpenAIClient secondClient = AzureOpenAiClientFactory.getInstance(ENDPOINT);
        assertSame(firstClient, secondClient);
    }

    @Test
    void getInstanceReturnsNewClientForDifferentEndpoints() {
        OpenAIClient firstClient = AzureOpenAiClientFactory.getInstance(ENDPOINT);
        OpenAIClient secondClient = AzureOpenAiClientFactory.getInstance(DIFFERENT_ENDPOINT);
        assertNotSame(firstClient, secondClient);
    }

    @Test
    void getInstanceThrowsExceptionForNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AzureOpenAiClientFactory.getInstance(null));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AzureOpenAiClientFactory.getInstance(""));
    }
}
