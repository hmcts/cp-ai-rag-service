package uk.gov.moj.cp.orchestrator.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.gov.moj.cp.orchestrator.extension.ProviderEnv.EMBEDDING_SERVICE_PROVIDER;
import static uk.gov.moj.cp.orchestrator.extension.ProviderEnv.LLM_CHAT_SERVICE_PROVIDER;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-18: the harness forwards both provider selectors to every function host, defaulting each to
 * {@code azure} so an unset environment reproduces today's behaviour.
 */
class ProviderEnvTest {

    @Test
    @DisplayName("Both provider variables are forwarded, defaulting to azure when unset")
    void defaultsBothProvidersToAzureWhenUnset() {
        final Map<String, String> entries = ProviderEnv.providerEnvEntries(key -> null);

        assertEquals(Set.of(LLM_CHAT_SERVICE_PROVIDER, EMBEDDING_SERVICE_PROVIDER), entries.keySet());
        assertEquals("azure", entries.get(LLM_CHAT_SERVICE_PROVIDER));
        assertEquals("azure", entries.get(EMBEDDING_SERVICE_PROVIDER));
    }

    @Test
    @DisplayName("A blank value is treated as unset and falls back to azure")
    void treatsBlankValueAsUnset() {
        final Map<String, String> entries = ProviderEnv.providerEnvEntries(key -> "");

        assertEquals("azure", entries.get(LLM_CHAT_SERVICE_PROVIDER));
        assertEquals("azure", entries.get(EMBEDDING_SERVICE_PROVIDER));
    }

    @Test
    @DisplayName("Values set in the environment are forwarded verbatim, per variable")
    void forwardsEnvironmentValuesVerbatim() {
        final Map<String, String> environment = Map.of(
                LLM_CHAT_SERVICE_PROVIDER, "azure",
                EMBEDDING_SERVICE_PROVIDER, "openai");

        final Map<String, String> entries = ProviderEnv.providerEnvEntries(environment::get);

        assertEquals("azure", entries.get(LLM_CHAT_SERVICE_PROVIDER));
        assertEquals("openai", entries.get(EMBEDDING_SERVICE_PROVIDER));
    }

    @Test
    @DisplayName("The process-environment reader returns both keys")
    void processEnvironmentReaderReturnsBothKeys() {
        assertEquals(Set.of(LLM_CHAT_SERVICE_PROVIDER, EMBEDDING_SERVICE_PROVIDER),
                ProviderEnv.providerEnvEntries().keySet());
    }
}
