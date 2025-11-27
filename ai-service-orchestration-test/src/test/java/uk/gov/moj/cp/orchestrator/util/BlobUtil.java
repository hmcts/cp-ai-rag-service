package uk.gov.moj.cp.orchestrator.util;

import static java.nio.file.Files.readAllBytes;
import static uk.gov.moj.cp.ai.util.CredentialUtil.getCredentialInstance;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobUtil {


    private static final Logger LOGGER = LoggerFactory.getLogger(BlobUtil.class);

    public static String uploadFile(final String endpoint, final String containerName, final String filename, final UUID documentId) {

        try {
            final String fileName = documentId.toString() + "_" + LocalDate.now() + ".pdf";
            final BlobContainerClient containerClient = getBlobContainerClient(endpoint, containerName);

            final byte[] payloadBytes = readAllBytes(Paths.get(ClassLoader.getSystemResource(filename).toURI()));
            final BlobClient blobClient = containerClient.getBlobClient(fileName);
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

    public static void ensureContainerExists(final String endpoint, final String containerName) {
        LOGGER.info("Connecting to '{}' and ensuring container '{}' exists...", endpoint, containerName);

        try {
            final BlobContainerClient containerClient = getBlobContainerClient(endpoint, containerName);

            containerClient.createIfNotExists();

            LOGGER.info("Container '{}' created successfully (or already existed).", containerName);
        } catch (Exception e) {
            // Handle or rethrow if the container could not be created
            throw new RuntimeException("Failed to create container", e);
        }
    }


    public static void deleteContainer(final String endpoint, final String containerName) {
        LOGGER.info("Connecting to '{}' and ensuring container '{}' exists before deleting it", endpoint, containerName);

        try {
            final BlobContainerClient containerClient = getBlobContainerClient(endpoint, containerName);

            final boolean containerDeleted = containerClient.deleteIfExists();

            LOGGER.info("Container '{}' deletion status {}", containerName, containerDeleted ? "deleted" : "not deleted");
        } catch (Exception e) {
            // Handle or rethrow if the container could not be created
            throw new RuntimeException("Failed to delete container", e);
        }
    }

    private static @NotNull BlobContainerClient getBlobContainerClient(final String endpoint, final String containerName) {
        return new BlobContainerClientBuilder()
                .endpoint(endpoint)
                .credential(getCredentialInstance())
                .containerName(containerName)
                .buildClient();
    }
}
