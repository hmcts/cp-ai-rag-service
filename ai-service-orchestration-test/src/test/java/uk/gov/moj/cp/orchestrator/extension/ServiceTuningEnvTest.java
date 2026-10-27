package uk.gov.moj.cp.orchestrator.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceTuningEnvTest {

    @Test
    @DisplayName("Nothing is forwarded when no tuning variable is set, so the hosts keep their code defaults")
    void forwardsNothingWhenUnset() {
        assertTrue(ServiceTuningEnv.tuningEnvEntries(key -> null).isEmpty());
        assertTrue(ServiceTuningEnv.tuningEnvEntries(key -> "").isEmpty());
    }

    @Test
    @DisplayName("Set tuning variables are forwarded verbatim; other variables are ignored")
    void forwardsOnlySetTuningVariables() {
        final Map<String, String> environment = Map.of(
                "SEARCH_MMR_FINAL_COUNT", "30",
                "INGESTION_LAYOUT_AWARE_TEXT_ENABLED", "false",
                "AZURE_OPENAI_ENDPOINT", "https://example.invalid");

        assertEquals(Map.of("SEARCH_MMR_FINAL_COUNT", "30", "INGESTION_LAYOUT_AWARE_TEXT_ENABLED", "false"),
                ServiceTuningEnv.tuningEnvEntries(environment::get));
    }
}
