package uk.gov.moj.cp.harness;

/**
 * Environment-variable access shared by the harness and its companion tools.
 *
 * <p>Deliberately free of static state: {@link TestHarness} initialises its run configuration
 * (required document ids, prompts, models) in static fields, so any class touching a
 * {@code TestHarness} static member forces that validation to run. Tools with different
 * requirements — {@link DocumentUploadTool} runs before any document ids exist — read the
 * environment through this class instead.
 */
final class HarnessEnv {

    private HarnessEnv() {
    }

    /** The variable's value, or {@code dflt} when unset/blank. */
    static String env(final String key, final String dflt) {
        final String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v;
    }

    /** The variable's value; fails fast with a pointer to the module's .env when unset/blank. */
    static String requireEnv(final String key) {
        final String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new RuntimeException("Required environment variable not set: " + key
                    + " (set it in the module's .env — the run-harness.sh/upload-document.sh scripts export it)");
        }
        return v;
    }

    /** The variable's value as an int, or {@code dflt} when unset/blank/non-numeric. */
    static int intEnv(final String key, final int dflt) {
        final String v = System.getenv(key);
        try {
            return (v == null || v.isBlank()) ? dflt : Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }
}
