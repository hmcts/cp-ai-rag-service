package uk.gov.moj.cp.orchestrator.util;

import java.time.Duration;

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

}