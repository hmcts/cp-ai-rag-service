package uk.gov.moj.cp.orchestrator.extension;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Creates the shared {@link RagHarness} once per test run: the first test class to start pays
 * for host startup and Azure resource creation, later classes reuse it, and JUnit closes the
 * harness when the root extension context ends — after ALL tests, regardless of failures.
 */
public class RagHarnessExtension implements BeforeAllCallback {

    private static final String HARNESS_KEY = "rag-service-harness";

    @Override
    public void beforeAll(final ExtensionContext context) {
        final RagHarness harness = context.getRoot()
                .getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent(HARNESS_KEY, key -> new RagHarness(), RagHarness.class);
        FunctionTestBase.bindHarness(harness);
    }
}
