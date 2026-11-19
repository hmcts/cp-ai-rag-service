package uk.gov.moj.cp.orchestrator.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ClaudeGatewayEnvTest {

    @Test
    void forwardsNothingWhenUnsetOrEmpty() {
        assertTrue(ClaudeGatewayEnv.claudeGatewayEnvEntries(key -> null).isEmpty());
        assertTrue(ClaudeGatewayEnv.claudeGatewayEnvEntries(key -> "").isEmpty());
    }

    @Test
    void forwardsOnlyTheSettingsThatAreSet() {
        final Map<String, String> environment = Map.of(
                "JUDGE_LLM_CHAT_SERVICE_PROVIDER", "claude-gateway",
                "AI_GATEWAY_TOKEN_SCOPE", "api://gateway-app/.default",
                "AI_GATEWAY_SUBSCRIPTION_KEY", "key",
                "UNRELATED", "ignored");

        assertEquals(Map.of(
                        "JUDGE_LLM_CHAT_SERVICE_PROVIDER", "claude-gateway",
                        "AI_GATEWAY_TOKEN_SCOPE", "api://gateway-app/.default",
                        "AI_GATEWAY_SUBSCRIPTION_KEY", "key"),
                ClaudeGatewayEnv.claudeGatewayEnvEntries(environment::get));
    }

    @Test
    void doesNotOverlapTheOtherForwardedSettings() {
        for (final String name : ClaudeGatewayEnv.FORWARDED) {
            assertTrue(!ServiceTuningEnv.FORWARDED.contains(name), name + " is also a tuning setting");
            assertTrue(!ProviderEnv.LLM_CHAT_SERVICE_PROVIDER.equals(name) && !ProviderEnv.EMBEDDING_SERVICE_PROVIDER.equals(name),
                    name + " is also a provider setting");
        }
    }
}
