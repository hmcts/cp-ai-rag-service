package uk.gov.moj.cp.ai.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;

import uk.gov.moj.cp.ai.util.EnvVarUtil;

import java.time.Duration;

import com.openai.core.Timeout;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * A-TDD scaffolding for OAI-01 (DD-43420) — OpenAI client retry/timeout hardening.
 *
 * <p>Covers AC-1, AC-2, AC-3 and AC-6 of {@code docs/pipeline/DD-43417-openai-sdk-migration/01-requirements.md}
 * against the not-yet-implemented {@code uk.gov.moj.cp.ai.client.config.OpenAiClientConfiguration}.
 *
 * <p>Mirrors {@link ClientConfigurationTest}'s established seam: {@code Mockito.mockStatic(EnvVarUtil.class)}
 * stubbing each {@code getRequiredEnvAsInteger(key, defaultValue)} call. Note that stubbing on the exact
 * {@code (key, defaultValue)} pair is itself load-bearing: an implementation that asks for a different
 * default string (for example openai-java's SDK default of {@code "2"} retries rather than this repo's
 * {@code "3"}) misses the stub, receives Mockito's {@code int} default of {@code 0}, and fails the assertion.
 */
class OpenAiClientConfigurationTest {

    private static final String MAX_RETRIES_VAR = "AZURE_CLIENT_MAX_RETRIES";
    private static final String RESPONSE_TIMEOUT_VAR = "HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS";
    private static final String CONNECT_TIMEOUT_VAR = "HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS";
    private static final String READ_TIMEOUT_VAR = "HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS";
    private static final String WRITE_TIMEOUT_VAR = "HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS";

    private static final String BASE_DELAY_VAR = "AZURE_CLIENT_BASE_DELAY_IN_SECONDS";
    private static final String MAX_DELAY_VAR = "AZURE_CLIENT_MAX_DELAY_IN_SECONDS";

    private static final String DEFAULT_MAX_RETRIES = "3";
    private static final String DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS = "180";
    private static final String DEFAULT_CONNECT_TIMEOUT_IN_SECONDS = "10";
    private static final String DEFAULT_READ_TIMEOUT_IN_SECONDS = "60";
    private static final String DEFAULT_WRITE_TIMEOUT_IN_SECONDS = "60";

    /**
     * AC-1: the configured value wins, not the SDK default of 2.
     */
    @Test
    void maxRetriesIsReadFromEnvironmentWhenSet() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, DEFAULT_MAX_RETRIES)).thenReturn(5);

            assertEquals(5, OpenAiClientConfiguration.getMaxRetries());

            mockedStatic.verify(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, DEFAULT_MAX_RETRIES));
        }
    }

    /**
     * AC-3: with nothing set, the documented repo default of 3 applies — deliberately NOT openai-java's
     * SDK default of 2 (OQ-2 / DD-5).
     */
    @Test
    void maxRetriesDefaultsToRepoDefaultOfThreeWhenEnvVarIsMissing() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, DEFAULT_MAX_RETRIES)).thenReturn(3);

            assertEquals(3, OpenAiClientConfiguration.getMaxRetries());

            mockedStatic.verify(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, DEFAULT_MAX_RETRIES));
        }
    }

    /**
     * AC-2: each HTTP_CLIENT_* variable lands on its mapped Timeout phase — response → request,
     * connect → connect, read → read, write → write.
     */
    @Test
    void timeoutPhasesAreReadFromEnvironmentWhenSet() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger(RESPONSE_TIMEOUT_VAR, DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS)).thenReturn(240);
            mockedStatic.when(() -> getRequiredEnvAsInteger(CONNECT_TIMEOUT_VAR, DEFAULT_CONNECT_TIMEOUT_IN_SECONDS)).thenReturn(20);
            mockedStatic.when(() -> getRequiredEnvAsInteger(READ_TIMEOUT_VAR, DEFAULT_READ_TIMEOUT_IN_SECONDS)).thenReturn(90);
            mockedStatic.when(() -> getRequiredEnvAsInteger(WRITE_TIMEOUT_VAR, DEFAULT_WRITE_TIMEOUT_IN_SECONDS)).thenReturn(30);

            final Timeout timeout = OpenAiClientConfiguration.getTimeout();

            assertNotNull(timeout);
            assertEquals(Duration.ofSeconds(240), timeout.request());
            assertEquals(Duration.ofSeconds(20), timeout.connect());
            assertEquals(Duration.ofSeconds(90), timeout.read());
            assertEquals(Duration.ofSeconds(30), timeout.write());

            mockedStatic.verify(() -> getRequiredEnvAsInteger(RESPONSE_TIMEOUT_VAR, DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS));
            mockedStatic.verify(() -> getRequiredEnvAsInteger(CONNECT_TIMEOUT_VAR, DEFAULT_CONNECT_TIMEOUT_IN_SECONDS));
            mockedStatic.verify(() -> getRequiredEnvAsInteger(READ_TIMEOUT_VAR, DEFAULT_READ_TIMEOUT_IN_SECONDS));
            mockedStatic.verify(() -> getRequiredEnvAsInteger(WRITE_TIMEOUT_VAR, DEFAULT_WRITE_TIMEOUT_IN_SECONDS));
        }
    }

    /**
     * AC-3: with nothing set, the documented repo defaults (180/10/60/60) apply rather than the
     * SDK's own 10-minute request timeout.
     */
    @Test
    void timeoutPhasesDefaultToDocumentedRepoDefaultsWhenEnvVarsAreMissing() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            stubAllTimeoutVarsAtTheirDocumentedDefaults(mockedStatic);

            final Timeout timeout = OpenAiClientConfiguration.getTimeout();

            assertNotNull(timeout);
            assertEquals(Duration.ofSeconds(180), timeout.request());
            assertEquals(Duration.ofSeconds(10), timeout.connect());
            assertEquals(Duration.ofSeconds(60), timeout.read());
            assertEquals(Duration.ofSeconds(60), timeout.write());
        }
    }

    /**
     * AC-6 / FR-3: openai-java's backoff curve is fixed (0.5s → 8s), so the Azure delay variables are a
     * documented no-op on this client. Setting them must neither throw nor change any produced value —
     * and the configuration must never read them at all.
     */
    @Test
    void baseAndMaxDelayEnvVarsAreANoOpAndDoNotAffectTheConfiguration() {

        try (MockedStatic<EnvVarUtil> mockedStatic = Mockito.mockStatic(EnvVarUtil.class)) {

            mockedStatic.when(() -> getRequiredEnvAsInteger(MAX_RETRIES_VAR, DEFAULT_MAX_RETRIES)).thenReturn(3);
            stubAllTimeoutVarsAtTheirDocumentedDefaults(mockedStatic);

            // The Azure-path delay variables are present in the environment.
            mockedStatic.when(() -> getRequiredEnvAsInteger(eq(BASE_DELAY_VAR), anyString())).thenReturn(7);
            mockedStatic.when(() -> getRequiredEnvAsInteger(eq(MAX_DELAY_VAR), anyString())).thenReturn(120);

            final int maxRetries = OpenAiClientConfiguration.getMaxRetries();
            final Timeout timeout = OpenAiClientConfiguration.getTimeout();

            // Identical to the defaults case — the delay variables changed nothing.
            assertEquals(3, maxRetries);
            assertEquals(Duration.ofSeconds(180), timeout.request());
            assertEquals(Duration.ofSeconds(10), timeout.connect());
            assertEquals(Duration.ofSeconds(60), timeout.read());
            assertEquals(Duration.ofSeconds(60), timeout.write());

            // Stronger than "did not throw": the delay variables are never even consulted.
            mockedStatic.verify(() -> getRequiredEnvAsInteger(eq(BASE_DELAY_VAR), anyString()), never());
            mockedStatic.verify(() -> getRequiredEnvAsInteger(eq(MAX_DELAY_VAR), anyString()), never());
        }
    }

    private void stubAllTimeoutVarsAtTheirDocumentedDefaults(final MockedStatic<EnvVarUtil> mockedStatic) {
        mockedStatic.when(() -> getRequiredEnvAsInteger(RESPONSE_TIMEOUT_VAR, DEFAULT_RESPONSE_TIMEOUT_IN_SECONDS)).thenReturn(180);
        mockedStatic.when(() -> getRequiredEnvAsInteger(CONNECT_TIMEOUT_VAR, DEFAULT_CONNECT_TIMEOUT_IN_SECONDS)).thenReturn(10);
        mockedStatic.when(() -> getRequiredEnvAsInteger(READ_TIMEOUT_VAR, DEFAULT_READ_TIMEOUT_IN_SECONDS)).thenReturn(60);
        mockedStatic.when(() -> getRequiredEnvAsInteger(WRITE_TIMEOUT_VAR, DEFAULT_WRITE_TIMEOUT_IN_SECONDS)).thenReturn(60);
    }
}
