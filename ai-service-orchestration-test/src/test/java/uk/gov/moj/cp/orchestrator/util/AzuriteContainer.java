package uk.gov.moj.cp.orchestrator.util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.azure.core.util.Context;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class AzuriteContainer extends GenericContainer<AzuriteContainer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzuriteContainer.class);

    private static final int BLOB_PORT = 10000;
    private static final int QUEUE_PORT = 10001;
    private static final int TABLE_PORT = 10002;

    public AzuriteContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));
        // Azurite by default supports Blob, Queue, and Table on ports 10000, 10001, and 10002.
        // Expose all three ports so Testcontainers can map them to random, available host ports.
        this.withExposedPorts(BLOB_PORT, QUEUE_PORT, TABLE_PORT)
                // Use a command that runs all services on 0.0.0.0 for access from the host.
                .withCommand("azurite -l /data --blobHost 0.0.0.0 --queueHost 0.0.0.0 --tableHost 0.0.0.0 --skipApiVersionCheck")
                .withStartupTimeout(Duration.ofSeconds(60));
    }

    public String getConnectionString() {
        return getConnectionString(this.getHost());
    }

    public String getConnectionString(final String host) {
        // Get the dynamically mapped ports for each service
        int blobPort = this.getMappedPort(BLOB_PORT);
        int queuePort = this.getMappedPort(QUEUE_PORT);
        int tablePort = this.getMappedPort(TABLE_PORT);

        // Default Azurite account name and key
        String accountName = "devstoreaccount1";
        String accountKey = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

        return String.format(
                "DefaultEndpointsProtocol=http;" +
                        "AccountName=%s;" +
                        "AccountKey=%s;" +
                        "BlobEndpoint=http://%s:%d/%s;" +
                        "QueueEndpoint=http://%s:%d/%s;" +
                        "TableEndpoint=http://%s:%d/%s;",
                accountName,
                accountKey,
                host, blobPort, accountName,
                host, queuePort, accountName,
                host, tablePort, accountName
        );
    }

    public void ensureContainerExists(final String containerName) {
        LOGGER.info("Connecting to Azurite and ensuring container '{}' exists...", containerName);

        try {
            LOGGER.info("Connection string is {}", getConnectionString());
            final BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(getConnectionString())
                    .containerName(containerName)
                    .buildClient();

            containerClient.createIfNotExists();

            LOGGER.info("Container '{}' created successfully (or already existed).", containerName);
        } catch (Exception e) {
            // Handle or rethrow if the container could not be created
            LOGGER.error("Failed to create container: {}", e.getMessage());
            throw new RuntimeException("Failed to set up Azurite secrets container.", e);
        }

        LOGGER.info("Container '{}' created successfully.", containerName);
    }

    public void ensureTableExists(String tableName) {
        LOGGER.info("Connecting to Azurite and ensuring Table '{}' exists...", tableName);

        try {
            TableServiceClient tableClient = new TableServiceClientBuilder()
                    .connectionString(getConnectionString())
                    .buildClient();

            // Get the Table Client and create the table if it doesn't exist.
            tableClient.createTableIfNotExists(tableName);

            LOGGER.info("Table '{}' created successfully (or already existed).", tableName);
        } catch (Exception e) {
            LOGGER.error("Failed to create Table '{}' in Azurite: {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to set up Azurite Table storage.", e);
        }
    }

    public String uploadFile(final String containerName, final String payload, final UUID documentId) {

        try {
            final String fileName = documentId.toString() + LocalDate.now() + ".pdf";
            final BlobContainerClient containerClient = new BlobContainerClientBuilder()
                    .connectionString(getConnectionString())
                    .containerName(containerName)
                    .buildClient();

            final byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            BlobClient blobClient = containerClient.getBlobClient(fileName);
            Map<String, String> customMetadata = Map.of("document_id", documentId.toString());

            BlobParallelUploadOptions options = new BlobParallelUploadOptions(new java.io.ByteArrayInputStream(payloadBytes))
                    .setMetadata(customMetadata);

            blobClient.uploadWithResponse(options,
                    Duration.ofSeconds(10),
                    Context.NONE
            ).getValue();

            LOGGER.info("Document {} uploaded successfully to folder {}", fileName, containerName);
            return fileName;


        } catch (Exception e) {
            // Handle or rethrow if the container could not be created
            LOGGER.error("Failed to create container: {}", e.getMessage());
            throw new RuntimeException("Failed to set up Azurite secrets container.", e);
        }
    }

    @FunctionalInterface
    private interface PollingCheck {
        void check() throws Exception;
    }

    /**
     * Implements a robust polling mechanism to wait for a storage resource to be truly ready.
     */
    private void waitForReady(String resourceName, PollingCheck check) throws InterruptedException {
        int MAX_RETRIES = 10;
        LOGGER.info("Starting polling for resource '{}' to stabilize...", resourceName);
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                check.check();
                LOGGER.info("Resource '{}' stabilized after {} attempts.", resourceName, i + 1);
                return;
            } catch (Exception e) {
                if (i == MAX_RETRIES - 1) {
                    LOGGER.error("Resource '{}' failed to stabilize after {} attempts.", resourceName, MAX_RETRIES);
                    throw new RuntimeException("Resource stabilization failed.", e);
                }
                TimeUnit.SECONDS.sleep(1);
            }
        }
    }

}