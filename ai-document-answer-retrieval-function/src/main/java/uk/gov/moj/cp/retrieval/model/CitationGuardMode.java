package uk.gov.moj.cp.retrieval.model;

import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Citation-guard policy, mapped from the {@code CITATION_GUARD_MODE} environment variable.
 * The guard itself (in {@code ResponseGenerationService}) throws
 * {@code CitationDegradedException} for any citation-degraded answer; this mode decides what a
 * caller does when its retries are exhausted:
 *
 * <ul>
 *   <li>{@link #DELIVER} (default) — the degraded answer is delivered, with the guard reason
 *       recorded for observability.</li>
 *   <li>{@link #REJECT} — the request fails with the guard reason; the uncited answer is never
 *       delivered.</li>
 *   <li>{@link #OFF} — the guard is disabled; any non-empty answer is accepted (pre-guard
 *       behaviour).</li>
 * </ul>
 *
 * <p>Retry budgets are owned by the callers: the async queue worker retries via queue
 * redelivery (up to {@code maxDequeueCount} attempts); the synchronous HTTP path performs no
 * retries and applies the policy immediately.
 */
public enum CitationGuardMode {

    DELIVER,
    REJECT,
    OFF;

    private static final Logger LOGGER = LoggerFactory.getLogger(CitationGuardMode.class);

    public static final String CITATION_GUARD_MODE = "CITATION_GUARD_MODE";

    /** Maps {@code CITATION_GUARD_MODE} (case-insensitive) to a mode; unknown values → DELIVER. */
    public static CitationGuardMode fromEnv() {
        final String value = getRequiredEnv(CITATION_GUARD_MODE, DELIVER.name()).trim();
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            LOGGER.warn("Unknown {} value '{}'; defaulting to {}.", CITATION_GUARD_MODE, value, DELIVER);
            return DELIVER;
        }
    }
}
