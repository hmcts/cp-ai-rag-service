package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import uk.gov.moj.cp.ai.util.EnvVarUtil;

import java.time.Duration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.Timeout;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class OpenAiClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String DIFFERENT_ENDPOINT = "https://different-endpoint.com";

    private static final String MAX_RETRIES_VAR = "AZURE_CLIENT_MAX_RETRIES";
    private static final String RESPONSE_TIMEOUT_VAR = "HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS";
    private static final String CONNECT_TIMEOUT_VAR = "HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS";
    private static final String READ_TIMEOUT_VAR = "HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS";
    private static final String WRITE_TIMEOUT_VAR = "HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS";

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

    // ---------------------------------------------------------------------------------------------
    // OAI-01 (DD-43420) — FR-2 / AC-1, AC-2, AC-3, AC-6.
    //
    // The client build is exercised through the package-private applyConfiguration(Builder) seam so the
    // configuration can be asserted behaviourally without reaching the network or defeating the
    // per-endpoint cache that getInstance() relies on (AC-4, covered unchanged above).
    // ---------------------------------------------------------------------------------------------

    /**
     * AC-1 / AC-2 / AC-3: the builder is given the values produced by {@code OpenAiClientConfiguration},
     * rather than being left on openai-java's own defaults (2 retries, 10-minute request timeout).
     */
    @Test
    void applyConfigurationAppliesMaxRetriesAndTimeoutFromOpenAiClientConfiguration() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            stubAllClientVarsAtTheirDocumentedDefaults(mockedStatic);

            final OpenAIOkHttpClient.Builder builder = spy(OpenAIOkHttpClient.builder());

            OpenAiClientFactory.applyConfiguration(builder);

            verify(builder).maxRetries(3);

            final ArgumentCaptor<Timeout> timeoutCaptor = ArgumentCaptor.forClass(Timeout.class);
            verify(builder).timeout(timeoutCaptor.capture());

            final Timeout appliedTimeout = timeoutCaptor.getValue();
            assertEquals(Duration.ofSeconds(180), appliedTimeout.request());
            assertEquals(Duration.ofSeconds(10), appliedTimeout.connect());
            assertEquals(Duration.ofSeconds(60), appliedTimeout.read());
            assertEquals(Duration.ofSeconds(60), appliedTimeout.write());
        }
    }

    /**
     * AC-6 / FR-3: the Azure backoff delay variables are a documented no-op on this client. With both set,
     * configuration still applies cleanly and the applied values are unchanged.
     */
    @Test
    void applyConfigurationSucceedsUnchangedWhenAzureBackoffDelayVarsAreSet() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            stubAllClientVarsAtTheirDocumentedDefaults(mockedStatic);
            mockedStatic.when(() -> getRequiredEnvAsInteger(eq("AZURE_CLIENT_BASE_DELAY_IN_SECONDS"), anyString())).thenReturn(7);
            mockedStatic.when(() -> getRequiredEnvAsInteger(eq("AZURE_CLIENT_MAX_DELAY_IN_SECONDS"), anyString())).thenReturn(120);

            final OpenAIOkHttpClient.Builder builder = spy(OpenAIOkHttpClient.builder());

            OpenAiClientFactory.applyConfiguration(builder);

            verify(builder).maxRetries(3);

            final ArgumentCaptor<Timeout> timeoutCaptor = ArgumentCaptor.forClass(Timeout.class);
            verify(builder).timeout(timeoutCaptor.capture());
            assertEquals(Duration.ofSeconds(180), timeoutCaptor.getValue().request());
        }
    }

    private void stubAllClientVarsAtTheirDocumentedDefaults(final MockedStatic<EnvVarUtil> mockedStatic) {
        mockedStatic.when(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, "3")).thenReturn(3);
        mockedStatic.when(() -> getRequiredEnvAsInteger(RESPONSE_TIMEOUT_VAR, "180")).thenReturn(180);
        mockedStatic.when(() -> getRequiredEnvAsInteger(CONNECT_TIMEOUT_VAR, "10")).thenReturn(10);
        mockedStatic.when(() -> getRequiredEnvAsInteger(READ_TIMEOUT_VAR, "60")).thenReturn(60);
        mockedStatic.when(() -> getRequiredEnvAsInteger(WRITE_TIMEOUT_VAR, "60")).thenReturn(60);
    }
}
