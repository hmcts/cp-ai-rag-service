package uk.gov.moj.cp.orchestrator.extension;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The per-capability model provider selectors ({@code LLM_CHAT_SERVICE_PROVIDER},
 * {@code EMBEDDING_SERVICE_PROVIDER}) forwarded from the test process environment to every function
 * host launched by {@link RagHarness} (FR-8). An unset variable falls back to {@code openai}, so an
 * integration run that says nothing about providers reproduces the production default exactly; an
 * integration leg that wants the Azure SDK rollback path exports the variable and the hosts follow.
 *
 * <p>Deliberately a separate class from {@link RagHarness}: the harness's static initialisers
 * create real Azure resources and require a fully-populated environment, so it cannot be loaded in
 * a plain unit test. This holder is env-only and touches no Azure client or static client state,
 * which is what makes the forwarding rule directly testable.</p>
 */
final class ProviderEnv {

    static final String LLM_CHAT_SERVICE_PROVIDER = "LLM_CHAT_SERVICE_PROVIDER";
    static final String EMBEDDING_SERVICE_PROVIDER = "EMBEDDING_SERVICE_PROVIDER";

    /** Matches the factories' default provider; flipped to {@code openai} with them at Stage 3 (DD-43423). */
    static final String DEFAULT_PROVIDER = "openai";

    private ProviderEnv() {
    }

    /** Provider entries resolved from the test process environment. */
    static Map<String, String> providerEnvEntries() {
        return providerEnvEntries(System::getenv);
    }

    // Package-private overload used by tests to bypass the System.getenv read.
    static Map<String, String> providerEnvEntries(final UnaryOperator<String> environment) {
        return Map.of(
                LLM_CHAT_SERVICE_PROVIDER, orDefault(environment.apply(LLM_CHAT_SERVICE_PROVIDER)),
                EMBEDDING_SERVICE_PROVIDER, orDefault(environment.apply(EMBEDDING_SERVICE_PROVIDER))
        );
    }

    private static String orDefault(final String value) {
        return isNullOrEmpty(value) ? DEFAULT_PROVIDER : value;
    }
}
