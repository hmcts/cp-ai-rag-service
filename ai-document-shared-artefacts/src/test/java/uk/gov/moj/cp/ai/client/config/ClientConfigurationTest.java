package uk.gov.moj.cp.ai.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import uk.gov.moj.cp.ai.util.EnvVarUtil;

import java.time.Duration;

import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.RetryOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ClientConfigurationTest {

    @Test
    void retryOptionsAreConfiguredWithDefaultValuesWhenEnvVarsAreMissing() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_RETRIES", "3")).thenReturn(5);
            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_BASE_DELAY_IN_SECONDS", "1")).thenReturn(10);
            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_DELAY_IN_SECONDS", "60")).thenReturn(15);


            RetryOptions retryOptions = ClientConfiguration.getRetryOptions();

            assertEquals(5, retryOptions.getExponentialBackoffOptions().getMaxRetries());
            assertEquals(Duration.ofSeconds(10), retryOptions.getExponentialBackoffOptions().getBaseDelay());
            assertEquals(Duration.ofSeconds(15), retryOptions.getExponentialBackoffOptions().getMaxDelay());

            // (Optional) Verify that the static method was actually called
            mockedStatic.verify(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_RETRIES", "3"));
            mockedStatic.verify(() -> getRequiredEnvAsInteger("AZURE_CLIENT_BASE_DELAY_IN_SECONDS", "1"));
            mockedStatic.verify(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_DELAY_IN_SECONDS", "60"));
        }
    }

    @Test
    void nettyClientIsConfiguredWithDefaultTimeoutsWhenEnvVarsAreMissing() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_RETRIES", "180")).thenReturn(5);
            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_BASE_DELAY_IN_SECONDS", "10")).thenReturn(10);
            mockedStatic.when(() -> getRequiredEnvAsInteger("AZURE_CLIENT_MAX_DELAY_IN_SECONDS", "60")).thenReturn(15);


            HttpClient httpClient = ClientConfiguration.createNettyClient();
            assertNotNull(httpClient);

            // (Optional) Verify that the static method was actually called
            mockedStatic.verify(() -> getRequiredEnvAsInteger("HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS", "180"));
            mockedStatic.verify(() -> getRequiredEnvAsInteger("HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS", "10"));
            mockedStatic.verify(() -> getRequiredEnvAsInteger("HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS", "60"));
        }
    }

}