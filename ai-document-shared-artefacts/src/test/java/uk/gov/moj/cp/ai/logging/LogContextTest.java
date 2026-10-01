package uk.gov.moj.cp.ai.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.logging.LogContext.CLIENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import com.microsoft.azure.functions.ExecutionContext;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("open clears stale keys left on a reused thread and seeds the invocation id")
    void open_clearsStaleKeysAndSeedsInvocationId() {
        MDC.put(TRANSACTION_ID, "stale");
        final ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getInvocationId()).thenReturn("inv-1");

        try (LogContext ignored = LogContext.open(ctx)) {
            assertNull(MDC.get(TRANSACTION_ID));
            assertEquals("inv-1", MDC.get(INVOCATION_ID));
        }
    }

    @Test
    @DisplayName("put adds keys learned mid-invocation and ignores null values")
    void put_addsKeysAndIgnoresNull() {
        try (LogContext ignored = LogContext.open(null)) {
            LogContext.put(TRANSACTION_ID, "txn-1");
            LogContext.put(DOCUMENT_ID, null);
            LogContext.put(CLIENT_ID, "client-1");

            assertEquals("txn-1", MDC.get(TRANSACTION_ID));
            assertEquals("txn-1", LogContext.get(TRANSACTION_ID));
            assertNull(MDC.get(DOCUMENT_ID));
            assertEquals("client-1", MDC.get(CLIENT_ID));
        }
    }

    @Test
    @DisplayName("close clears every key so the next invocation on the thread starts clean")
    void close_clearsEveryKey() {
        final ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getInvocationId()).thenReturn("inv-1");

        try (LogContext ignored = LogContext.open(ctx)) {
            LogContext.put(TRANSACTION_ID, "txn-1");
            LogContext.put(CLIENT_ID, "client-1");
        }

        assertNull(MDC.get(INVOCATION_ID));
        assertNull(MDC.get(TRANSACTION_ID));
        assertNull(MDC.get(CLIENT_ID));
    }

    @Test
    @DisplayName("a null execution context (unit tests) opens an empty scope without failing")
    void open_toleratesNullContext() {
        try (LogContext ignored = LogContext.open(null)) {
            assertNull(MDC.get(INVOCATION_ID));
        }
    }

    @Test
    @DisplayName("SLF4J MDC keys are visible to log4j2's ThreadContext, which the %X pattern reads")
    void mdc_isBridgedToLog4jThreadContext() {
        try (LogContext ignored = LogContext.open(null)) {
            LogContext.put(DOCUMENT_ID, "doc-1");
            assertEquals("doc-1", ThreadContext.get(DOCUMENT_ID));
        }
        assertNull(ThreadContext.get(DOCUMENT_ID));
    }
}
