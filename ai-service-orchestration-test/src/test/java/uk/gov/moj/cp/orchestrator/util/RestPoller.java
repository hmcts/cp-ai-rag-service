package uk.gov.moj.cp.orchestrator.util;

import static java.lang.System.currentTimeMillis;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestPoller.class);

    private final static long POLL_INTERVAL_SECONDS = 2L; // Poll every 1 second
    private final static long TIMEOUT_IN_SECONDS = 60L; // Poll every 1 second

    public static Response pollForResponse(RequestSpecification requestSpec,
                                           RestOperation operation,
                                           String path,
                                           Predicate<Response> successCondition)
            throws InterruptedException, TimeoutException {

        LOGGER.info("Starting HTTP polling on path {} for custom condition...", path);
        final long startTime = currentTimeMillis();
        final long endTime = startTime + (TIMEOUT_IN_SECONDS * 1000L);
        final AtomicReference<Response> response = new AtomicReference<Response>();

        await()
                .atMost(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)
                .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .ignoreExceptions()
                .until(() -> {
                    response.set((operation == RestOperation.POST) ? requestSpec.post(path) : requestSpec.get(path));
                    if (successCondition.test(response.get())) {
                        LOGGER.info("Polling successful. Custom condition met for path {}.", path);
                        return true;
                    }
                    LOGGER.debug("Polling attempt failed. Custom condition NOT met. Status: {}. Retrying...", response.get().getStatusCode());
                    return false;
                });

        if (null == response.get()) {
            throw new TimeoutException("Polling timed out without receiving a valid response.");
        }

        return response.get();
    }

    public static Response postRequest(RequestSpecification requestSpec,
                                       String path,
                                       Predicate<Response> successCondition) {

        LOGGER.info("Starting POST request for path '{};", path);
        final AtomicReference<Response> response = new AtomicReference<Response>();

        response.set(requestSpec.post(path));
        if (successCondition.test(response.get())) {
            LOGGER.info("Successfully posted request to '{}'.", path);
            return response.get();
        }
        fail();
        return null;
    }
}
