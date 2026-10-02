package uk.gov.moj.cp.ai.logging;

import com.microsoft.azure.functions.ExecutionContext;
import org.slf4j.MDC;

/**
 * Thread-local log context for one function invocation.
 *
 * <p>Every SLF4J line written on the invocation thread while this is open carries the keys put
 * here, via log4j2's {@code ThreadContext} (bridged by {@code log4j-slf4j2-impl}) and the
 * {@code %X{...}} tokens in each app's {@code log4j2.xml} pattern. Service and shared-artefacts
 * classes need no knowledge of it: they log as they always have and inherit the keys.
 *
 * <p>Two journeys run through the service and each has one key: {@link #TRANSACTION_ID} on the
 * answer journey (initiate → generate → status → score) and {@link #DOCUMENT_ID} on the document
 * journey (upload → upload check → ingestion → status). {@link #INVOCATION_ID} is on every
 * invocation and is the only correlation key the synchronous answer endpoint has; that endpoint
 * forwards it to its scoring run as {@link #ORIGIN_INVOCATION_ID}.
 *
 * <p>Always opened in try-with-resources around the whole function body: the Functions host reuses
 * worker threads, so a leaked key would attach one request's ID to the next request's lines.
 * {@link #open(ExecutionContext)} also clears on entry for the same reason.
 *
 * <p>The context is thread-local. Every pipeline in this repo is single-threaded per invocation;
 * if an executor or parallel stream is ever introduced on a path, copy the keys across with
 * {@link MDC#getCopyOfContextMap()} / {@link MDC#setContextMap(java.util.Map)} in the submitted task.
 *
 * <p>Design: {@code docs/transaction-scoped-logging.md}.
 */
public final class LogContext implements AutoCloseable {

    /** The Functions host's per-invocation ID; present on every invocation. Printed as {@code inv=}. */
    public static final String INVOCATION_ID = "invocationId";
    /** Answer-journey key. Printed as {@code txn=}. */
    public static final String TRANSACTION_ID = "transactionId";
    /** Document-journey key. Printed as {@code doc=}. */
    public static final String DOCUMENT_ID = "documentId";
    /** The synchronous answer endpoint's invocation ID, on the scoring run it produced. Printed as {@code origin=}. */
    public static final String ORIGIN_INVOCATION_ID = "originInvocationId";
    /** Resolved client identity when {@code CLIENT_FILTERING_ENABLED} is on. Printed as {@code client=}. */
    public static final String CLIENT_ID = "clientId";

    private LogContext() {
        MDC.clear(); // never inherit a previous invocation's keys on a reused thread
    }

    /**
     * Opens the context for one invocation, seeded with the host's invocation ID. A null context
     * (unit tests) opens an empty scope.
     */
    public static LogContext open(final ExecutionContext context) {
        final LogContext scope = new LogContext();
        if (context != null) {
            put(INVOCATION_ID, context.getInvocationId());
        }
        return scope;
    }

    /**
     * Adds a key learned part-way through the invocation (for example the transaction ID once the
     * queue payload is parsed). Null values are ignored so callers need not guard optional fields.
     */
    public static void put(final String key, final String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    /** The current value of a key on this thread, or null. */
    public static String get(final String key) {
        return MDC.get(key);
    }

    @Override
    public void close() {
        MDC.clear();
    }
}
