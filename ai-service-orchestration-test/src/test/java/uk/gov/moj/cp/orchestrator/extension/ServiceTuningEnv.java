package uk.gov.moj.cp.orchestrator.extension;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Ingestion, retrieval and generation tuning variables forwarded from the test process environment
 * to every function host launched by {@link RagHarness}, but only when set. An unset variable is
 * not forwarded, so the hosts keep their code defaults and the existing integration legs behave
 * exactly as before; the eval leg (DD-43887) sets them to production values so its results
 * reflect what production runs.
 *
 * <p>Env-only and free of Azure clients, like {@link ProviderEnv}, so the rule is unit-testable.</p>
 */
public final class ServiceTuningEnv {

    static final List<String> FORWARDED = List.of(
            "INGESTION_LAYOUT_AWARE_TEXT_ENABLED",
            "SEARCH_NEAREST_NEIGHBOURS_COUNT",
            "SEARCH_TOP_RESULTS_COUNT",
            "SEARCH_RESULTS_ENABLE_CONTAINMENT_DEDUP",
            "SEARCH_CONTAINMENT_SHINGLE_SIZE",
            "SEARCH_CONTAINMENT_THRESHOLD",
            "SEARCH_RESULTS_ENABLE_DEDUPLICATION",
            "SEARCH_RESULTS_SEMANTIC_DEDUPLICATION_THRESHOLD",
            "SEARCH_RESULTS_ENABLE_MMR",
            "SEARCH_MMR_LAMBDA",
            "SEARCH_MMR_FINAL_COUNT",
            "LLM_MODEL_RESPONSE_MAX_TOKENS",
            "LLM_REASONING_EFFORT");

    private ServiceTuningEnv() {
    }

    /** The tuning entries set in the test process environment, in a stable order. */
    public static Map<String, String> tuningEnvEntries() {
        return tuningEnvEntries(System::getenv);
    }

    // Package-private overload used by tests to bypass the System.getenv read.
    static Map<String, String> tuningEnvEntries(final UnaryOperator<String> environment) {
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
