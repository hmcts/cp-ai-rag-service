package uk.gov.moj.cp.orchestrator.extension;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Settings for Claude through the AI gateway (DD-44006), forwarded from the test process
 * environment to every function host launched by {@link RagHarness}, but only when set. With none
 * of them set the hosts are started exactly as before.
 *
 * <p>{@code JUDGE_LLM_CHAT_SERVICE_PROVIDER} lets the scoring judge use a different provider from answer
 * generation: every host receives the same settings. Answer generation's own switch,
 * {@code LLM_CHAT_SERVICE_PROVIDER}, is pinned per failsafe leg in the pom ({@code it.llm.provider}) and
 * forwarded by {@link ProviderEnv}.</p>
 *
 * <p>Env-only and free of Azure clients, like {@link ProviderEnv}, so the rule is unit-testable.</p>
 */
final class ClaudeGatewayEnv {

    static final List<String> FORWARDED = List.of(
            "JUDGE_LLM_CHAT_SERVICE_PROVIDER",
            "AI_GATEWAY_BASE_URL",
            "AI_GATEWAY_CHAT_MODEL",
            "AI_GATEWAY_JUDGE_MODEL",
            "AI_GATEWAY_TOKEN_SCOPE",
            "AI_GATEWAY_SUBSCRIPTION_KEY",
            "AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION",
            "LLM_ANTHROPIC_TEMPERATURE",
            "LLM_ANTHROPIC_THINKING",
            "LLM_ANTHROPIC_EFFORT");

    private ClaudeGatewayEnv() {
    }

    /** The gateway entries set in the test process environment, in a stable order. */
    static Map<String, String> claudeGatewayEnvEntries() {
        return claudeGatewayEnvEntries(System::getenv);
    }

    // Package-private overload used by tests to bypass the System.getenv read.
    static Map<String, String> claudeGatewayEnvEntries(final UnaryOperator<String> environment) {
        final Map<String, String> entries = new LinkedHashMap<>();
        for (final String name : FORWARDED) {
            final String value = environment.apply(name);
            if (!isNullOrEmpty(value)) {
                entries.put(name, value);
            }
        }
        return entries;
    }
}
