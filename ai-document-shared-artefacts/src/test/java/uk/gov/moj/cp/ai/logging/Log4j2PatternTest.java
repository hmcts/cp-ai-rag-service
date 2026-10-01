package uk.gov.moj.cp.ai.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.moj.cp.ai.logging.LogContext.CLIENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.ORIGIN_INVOCATION_ID;
import static uk.gov.moj.cp.ai.logging.LogContext.TRANSACTION_ID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Proves the end-to-end claim of the logging design: a line written by any class, with no
 * knowledge of {@link LogContext}, renders with the journey keys because the deployed
 * {@code log4j2.xml} pattern prints them — and that the five function apps share one pattern.
 */
class Log4j2PatternTest {

    private static final Pattern PATTERN_ATTRIBUTE = Pattern.compile("pattern=\"([^\"]+)\"");

    /** Function-app log4j2 files, relative to this module's directory (surefire's working directory). */
    static Stream<Path> functionAppLog4j2Files() {
        return Stream.of(
                "ai-document-answer-retrieval-function",
                "ai-document-answer-scoring-function",
                "ai-document-ingestion-function",
                "ai-document-metadata-check-function",
                "ai-document-status-check-function"
        ).map(module -> Paths.get("..", module, "src", "main", "resources", "log4j2.xml"));
    }

    @AfterEach
    void clearThread() {
        MDC.clear();
    }

    @Test
    @DisplayName("all five function apps declare the same appender pattern")
    void allFunctionAppsShareOnePattern() {
        final List<String> patterns = functionAppLog4j2Files().map(Log4j2PatternTest::patternOf).distinct().toList();
        assertEquals(1, patterns.size(), "function-app log4j2.xml patterns have diverged: " + patterns);
    }

    @Test
    @DisplayName("a service line inside an open scope carries the journey keys")
    void serviceLineInsideScopeCarriesKeys() {
        functionAppLog4j2Files().forEach(this::assertServiceLineInsideScopeCarriesKeys);
    }

    private void assertServiceLineInsideScopeCarriesKeys(final Path log4j2File) {
        final PatternLayout layout = layoutOf(log4j2File);

        final String rendered;
        try (LogContext ignored = LogContext.open(null)) {
            LogContext.put(INVOCATION_ID, "inv-1");
            LogContext.put(TRANSACTION_ID, "txn-1");
            LogContext.put(CLIENT_ID, "client-1");
            rendered = layout.toSerializable(eventFromCurrentThread("Citation guard: delivering answer"));
        }

        assertTrue(rendered.contains("[txn=txn-1]"), rendered);
        assertTrue(rendered.contains("[inv=inv-1]"), rendered);
        assertTrue(rendered.contains("[client=client-1]"), rendered);
        assertFalse(rendered.contains("[doc="), rendered);
        assertFalse(rendered.contains("[origin="), rendered);
        assertTrue(rendered.endsWith("Citation guard: delivering answer" + System.lineSeparator()), rendered);
    }

    @Test
    @DisplayName("document-journey and origin keys render under their own tokens")
    void documentJourneyAndOriginKeysRender() {
        functionAppLog4j2Files().forEach(this::assertDocumentJourneyAndOriginKeysRender);
    }

    private void assertDocumentJourneyAndOriginKeysRender(final Path log4j2File) {
        final PatternLayout layout = layoutOf(log4j2File);

        final String rendered;
        try (LogContext ignored = LogContext.open(null)) {
            LogContext.put(DOCUMENT_ID, "doc-1");
            LogContext.put(ORIGIN_INVOCATION_ID, "origin-1");
            rendered = layout.toSerializable(eventFromCurrentThread("Document chunking completed"));
        }

        assertTrue(rendered.contains("[doc=doc-1]"), rendered);
        assertTrue(rendered.contains("[origin=origin-1]"), rendered);
        assertFalse(rendered.contains("[txn="), rendered);
    }

    @Test
    @DisplayName("a line outside any scope (startup, factories) renders with no empty brackets")
    void lineOutsideScopeStaysClean() {
        functionAppLog4j2Files().forEach(this::assertLineOutsideScopeStaysClean);
    }

    private void assertLineOutsideScopeStaysClean(final Path log4j2File) {
        final PatternLayout layout = layoutOf(log4j2File);

        final String rendered = layout.toSerializable(eventFromCurrentThread("Client factory initialised"));

        // %notEmpty{} must drop the whole " [key=]" token, not print an empty one; only the
        // pattern's own [%t] thread bracket may remain.
        for (String token : List.of("[txn=", "[doc=", "[origin=", "[inv=", "[client=")) {
            assertFalse(rendered.contains(token), "unexpected " + token + " in: " + rendered);
        }
        assertTrue(rendered.contains("ResponseGenerationService - Client factory initialised"), rendered);
    }

    private static String patternOf(final Path log4j2File) {
        try {
            final Matcher matcher = PATTERN_ATTRIBUTE.matcher(Files.readString(log4j2File));
            assertTrue(matcher.find(), "no PatternLayout pattern in " + log4j2File);
            return matcher.group(1);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + log4j2File.toAbsolutePath(), e);
        }
    }

    private static PatternLayout layoutOf(final Path log4j2File) {
        return PatternLayout.newBuilder()
                .setPattern(patternOf(log4j2File))
                .setConfiguration(new DefaultConfiguration())
                .build();
    }

    /** A log event as log4j2 would build it on this thread: the message plus the thread's context map. */
    private static LogEvent eventFromCurrentThread(final String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("uk.gov.moj.cp.retrieval.service.ResponseGenerationService")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .setContextData(new SortedArrayStringMap(ThreadContext.getImmutableContext()))
                .build();
    }
}
