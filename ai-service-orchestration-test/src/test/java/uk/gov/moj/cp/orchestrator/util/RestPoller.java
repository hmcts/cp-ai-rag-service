package uk.gov.moj.cp.orchestrator.util;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestPoller.class);

    /** Interval for cheap polls (status GETs and other reads costing nothing but the request). */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * Interval for polls whose every attempt is a full embed → search → LLM round trip (the sync
     * answer endpoint): the wider spacing protects model spend, not wall-clock time.
     */
    public static final Duration LLM_POLL_INTERVAL = Duration.ofSeconds(5);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public static Response pollForResponse(final RequestSpecification requestSpec,
                                           final RestOperation operation,
                                           final String path,
                                           final Predicate<Response> successCondition) throws TimeoutException {
        return pollForResponse(requestSpec, operation, path, successCondition, DEFAULT_TIMEOUT);
    }

    public static Response pollForResponse(final RequestSpecification requestSpec,
                                           final RestOperation operation,
                                           final String path,
                                           final Predicate<Response> successCondition,
                                           final Duration timeout) throws TimeoutException {
        return pollForResponse(requestSpec, operation, path, successCondition, timeout, POLL_INTERVAL);
    }

    public static Response pollForResponse(final RequestSpecification requestSpec,
                                           final RestOperation operation,
                                           final String path,
                                           final Predicate<Response> successCondition,
                                           final Duration timeout,
                                           final Duration pollInterval) throws TimeoutException {

        LOGGER.info("Starting HTTP polling on path {} for custom condition (timeout {})...", path, timeout);
        final AtomicReference<Response> lastResponse = new AtomicReference<>();
        final AtomicReference<Exception> lastException = new AtomicReference<>();

        try {
            await()
                    .atMost(timeout)
                    .pollInterval(pollInterval)
                    .until(() -> {
                        try {
                            lastResponse.set((operation == RestOperation.POST) ? requestSpec.post(path) : requestSpec.get(path));
                            lastException.set(null);
                        } catch (Exception e) {
                            // Tolerate transient request failures but keep them visible for the timeout report
                            lastException.set(e);
                            LOGGER.warn("Polling request to {} failed: {}", path, e.getMessage());
                            return false;
                        }
                        if (successCondition.test(lastResponse.get())) {
                            LOGGER.info("Polling successful. Custom condition met for path {}.", path);
                            return true;
                        }
                        LOGGER.debug("Polling attempt failed. Custom condition NOT met. Status: {}. Retrying...",
                                lastResponse.get().getStatusCode());
                        return false;
                    });
        } catch (ConditionTimeoutException e) {
            throw new TimeoutException(describeTimeout(path, timeout, lastResponse.get(), lastException.get()));
        }

        return lastResponse.get();
    }

    private static String describeTimeout(final String path,
                                          final Duration timeout,
                                          final Response lastResponse,
                                          final Exception lastException) {
        final StringBuilder message = new StringBuilder("Polling ").append(path)
                .append(" timed out after ").append(timeout).append(" without meeting the condition.");
        if (lastResponse != null) {
            message.append(" Last response: HTTP ").append(lastResponse.getStatusCode())
                    .append(" body: ").append(lastResponse.getBody().asString());
        }
        if (lastException != null) {
            message.append(" Last request failure: ").append(lastException);
        }
        return message.toString();
    }

    public static Response postRequest(final RequestSpecification requestSpec,
                                       final String path,
                                       final Predicate<Response> successCondition) {

        LOGGER.info("Starting POST request for path '{}'", path);
        final Response response = requestSpec.post(path);
        if (successCondition.test(response)) {
            LOGGER.info("Successfully posted request to '{}'.", path);
            return response;
        }
        fail("Received response: " + response.prettyPrint() + " for path: " + path + ". Expected condition not met.");
        return null;
    }
}
