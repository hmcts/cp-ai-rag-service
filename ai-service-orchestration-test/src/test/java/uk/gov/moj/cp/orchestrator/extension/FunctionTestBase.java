package uk.gov.moj.cp.orchestrator.extension;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base class for integration tests that need the local Azure Function hosts running. The heavy
 * fixture (five {@code func} hosts plus the per-run blob containers, queues and tables) is owned
 * by {@link RagHarness}: {@link RagHarnessExtension} creates it once per test run — the first
 * test class to start pays for it, later classes reuse it — and JUnit tears it down after the
 * whole run. Subclasses reach everything through {@link #harness()}.
 */
@ExtendWith(RagHarnessExtension.class)
public abstract class FunctionTestBase {

    private static RagHarness harness;

    static void bindHarness(final RagHarness ragHarness) {
        harness = ragHarness;
    }

    protected static RagHarness harness() {
        return harness;
    }
}
