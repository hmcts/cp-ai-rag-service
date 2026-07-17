package uk.gov.moj.cp.orchestrator.util;

import static java.lang.Thread.currentThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to manage the lifecycle of a local Azure Functions host process.
 * Startup is split into {@link #launch(Map)} and {@link #awaitReady()} so callers can start
 * several hosts concurrently and only then wait for each of them to become ready.
 */
public class FunctionHostManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionHostManager.class);

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);

    private Process functionProcess;
    private final String appDirectory;
    private final String friendlyDirectoryName;
    private final int port;

    public FunctionHostManager(String appDirectory, int port) {
        this.appDirectory = appDirectory;
        this.port = port;
        this.friendlyDirectoryName = appDirectory.lastIndexOf("/") == -1 ? appDirectory : appDirectory.substring(appDirectory.lastIndexOf("/") + 1);
    }

    /**
     * Spawns the Azure Functions host process without waiting for it to become ready.
     */
    public void launch(final Map<String, String> environmentVariables) throws IOException {
        final ProcessBuilder processBuilder = getProcessBuilder(environmentVariables);

        LOGGER.info("Starting Azure Function App {} on port {}...", friendlyDirectoryName, port);

        functionProcess = processBuilder.start();

        // Read the stream in a separate thread to prevent the process from hanging
        final Thread outputDrainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(functionProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("[{}] {}", friendlyDirectoryName, line);
                }
            } catch (IOException e) {
                LOGGER.error("[{}] Error reading Function host output", friendlyDirectoryName, e);
            }
        }, "func-output-" + friendlyDirectoryName);
        outputDrainer.setDaemon(true);
        outputDrainer.start();
    }

    /**
     * Blocks until the host reports itself running, killing the process on timeout so a failed
     * startup never leaks a live {@code func} process.
     */
    public void awaitReady() throws TimeoutException, InterruptedException {
        try {
            waitForHostReady();
        } catch (TimeoutException e) {
            stop();
            throw e;
        }
        LOGGER.info("Function App ({}) started on http://localhost:{}", friendlyDirectoryName, port);
    }

    private ProcessBuilder getProcessBuilder(final Map<String, String> environmentVariables) {
        final ProcessBuilder processBuilder = new ProcessBuilder(
                "func", "host", "start", "--port", String.valueOf(port)
        );

        // Set the working directory to the compiled function app path
        processBuilder.directory(new File(appDirectory));

        Map<String, String> environment = processBuilder.environment();
        environment.putAll(environmentVariables);


        // This ensures the stdout and stderr streams from the 'func host start' process
        // are merged and streamed directly to the console where your Java test is running.
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    /**
     * Stops and destroys the running Azure Functions host process.
     */
    public void stop() {
        if (functionProcess != null) {
            LOGGER.info("Stopping Azure Function App ({}) on port {}...", friendlyDirectoryName, port);
            // Forcefully terminate the process
            functionProcess.destroyForcibly();
            try {
                // Wait for the process to exit
                final boolean exited = functionProcess.waitFor(10, TimeUnit.SECONDS);
                if (exited) {
                    LOGGER.info("Function App ({}) stopped successfully.", friendlyDirectoryName);
                } else {
                    LOGGER.warn("Function App ({}) did not exit within 10s of being destroyed.", friendlyDirectoryName);
                }
            } catch (InterruptedException e) {
                currentThread().interrupt();
                LOGGER.error("Error while stopping function host: {}", e.getMessage());
            }
        }
    }

    /**
     * A host can accept TCP connections before the Java worker has loaded its functions, so
     * readiness polls the host's admin status endpoint (unauthenticated when run via Core Tools)
     * until it answers 200 rather than merely probing the socket.
     */
    private void waitForHostReady() throws TimeoutException, InterruptedException {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        final HttpRequest statusRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/admin/host/status"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        final long deadline = System.currentTimeMillis() + READY_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!functionProcess.isAlive()) {
                throw new TimeoutException("Azure Function (" + friendlyDirectoryName + ") process exited during startup with code "
                        + functionProcess.exitValue());
            }
            try {
                final HttpResponse<String> response = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    LOGGER.info("Azure Function App ({}) on port {} reports host status: {}", friendlyDirectoryName, port, response.body());
                    return;
                }
            } catch (IOException e) {
                // Not ready yet
            }
            Thread.sleep(500);
        }

        throw new TimeoutException("Azure Function (" + friendlyDirectoryName + ") failed to become ready on port " + port
                + " within " + READY_TIMEOUT);
    }
}
