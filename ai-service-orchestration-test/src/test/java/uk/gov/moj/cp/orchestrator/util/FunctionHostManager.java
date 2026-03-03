package uk.gov.moj.cp.orchestrator.util;

import static java.lang.Thread.currentThread;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to manage the lifecycle of a local Azure Functions host process.
 */
public class FunctionHostManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionHostManager.class);

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
     * Starts the Azure Functions host process asynchronously.
     *
     * @throws IOException          If the command fails to execute.
     * @throws InterruptedException If the startup thread is interrupted.
     */
    public void start(final Map<String, String> environmentVariables) throws IOException, InterruptedException, TimeoutException {
        // Command to execute: func host start --port 7071
        final ProcessBuilder processBuilder = getProcessBuilder(environmentVariables);

        LOGGER.info("Starting Azure Function App {} on port {}...", friendlyDirectoryName, port);

        functionProcess = processBuilder.start();

        waitForHostReady();

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
        processBuilder.inheritIO();
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
                functionProcess.waitFor(10, TimeUnit.SECONDS);
                LOGGER.info("Function App ({}) stopped successfully.", friendlyDirectoryName);
            } catch (InterruptedException e) {
                currentThread().interrupt();
                LOGGER.error("Error while stopping function host: {}", e.getMessage());
            }
        }
    }

    private void waitForHostReady() throws TimeoutException, InterruptedException {
        long timeoutMillis = 60000; // 60 seconds timeout
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
                LOGGER.info("Azure Function App ({}) on port {} is now reachable.", friendlyDirectoryName, port);
                return; // Success!
            } catch (IOException e) {
                // Not ready yet, wait a bit
                Thread.sleep(500);
            }
        }

        throw new TimeoutException("Azure Function (" + friendlyDirectoryName + ") failed to start on port " + port + " within " + timeoutMillis + "ms");
    }
}