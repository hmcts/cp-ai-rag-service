package uk.gov.moj.cp.orchestrator.util;

import static java.lang.System.currentTimeMillis;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestPoller.class);

    private final static long POLL_INTERVAL_SECONDS = 1L; // Poll every 1 second
    private final static long TIMEOUT_IN_SECONDS = 30L; // Poll every 1 second

    public static void pollForResponseCondition(RequestSpecification requestSpec,
                                                String path,
                                                Predicate<Response> successCondition)
            throws InterruptedException, TimeoutException {

        LOGGER.info("Starting HTTP polling on path {} for custom condition...", path);
        final long startTime = currentTimeMillis();
        final long endTime = startTime + (TIMEOUT_IN_SECONDS * 1000L);


        while (currentTimeMillis() < endTime) {
            try {
                Response response = requestSpec.get(path);

                if (successCondition.test(response)) {
                    LOGGER.info("Polling successful. Custom condition met for path {}.", path);
                    return;
                }

                LOGGER.debug("Polling attempt failed. Custom condition NOT met. Status: {}. Retrying...", response.getStatusCode());

            } catch (Exception e) {
                LOGGER.error("Polling failed due to exception: {}. Retrying...", e.getMessage());
            }

            TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
        }

        throw new TimeoutException("Polling failed: Timeout reached after " + TIMEOUT_IN_SECONDS + " seconds while waiting for the custom condition on path " + path);
    }
}
