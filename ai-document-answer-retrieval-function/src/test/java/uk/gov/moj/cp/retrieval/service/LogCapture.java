package uk.gov.moj.cp.retrieval.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Test helper that captures the log4j2 events written to the named loggers while it is open.
 * Use in try-with-resources so the appender is always detached:
 *
 * <pre>{@code
 * try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
 *     ...
 *     assertEquals(1, logs.messages(Level.WARN).size());
 * }
 * }</pre>
 */
final class LogCapture implements AutoCloseable {

    private final List<Logger> loggers = new ArrayList<>();
    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final AbstractAppender appender;

    private LogCapture(final Class<?>... sources) {
        appender = new AbstractAppender("log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        for (final Class<?> source : sources) {
            final Logger logger = (Logger) LogManager.getLogger(source);
            logger.addAppender(appender);
            loggers.add(logger);
        }
    }

    static LogCapture of(final Class<?>... sources) {
        return new LogCapture(sources);
    }

    /** Formatted messages captured at exactly {@code level}, in order. */
    List<String> messages(final Level level) {
        synchronized (events) {
            return events.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(event -> event.getMessage().getFormattedMessage())
                    .toList();
        }
    }

    /** Every formatted message captured, in order. */
    List<String> messages() {
        synchronized (events) {
            return events.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
        }
    }

    @Override
    public void close() {
        loggers.forEach(logger -> logger.removeAppender(appender));
        appender.stop();
    }
}
