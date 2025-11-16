package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class BlobContainerClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String CONTAINER_NAME = "example-container";

    @Test
    void getInstanceCreatesNewBlobServiceClientWhenNotInCache() {
        BlobContainerClient client = BlobContainerClientFactory.getInstance(ENDPOINT, CONTAINER_NAME);
        assertNotNull(client);
    }

    @Test
    void getInstanceReturnsCachedBlobServiceClientForSameEndpointAndContainerName() {
        BlobContainerClient firstClient = BlobContainerClientFactory.getInstance(ENDPOINT, CONTAINER_NAME);
        BlobContainerClient secondClient = BlobContainerClientFactory.getInstance(ENDPOINT, CONTAINER_NAME);
        assertSame(firstClient, secondClient);
    }

    @Test
    void getInstanceReturnsNewBlobServiceClientForDifferentEndpointOrContainerName() {
        BlobContainerClient firstClient = BlobContainerClientFactory.getInstance(ENDPOINT, CONTAINER_NAME);
        BlobContainerClient secondClient = BlobContainerClientFactory.getInstance(ENDPOINT, "different-container");
        BlobContainerClient thirdClient = BlobContainerClientFactory.getInstance("https://different-endpoint.com", CONTAINER_NAME);
        assertNotSame(firstClient, secondClient);
        assertNotSame(firstClient, thirdClient);
        assertNotSame(secondClient, thirdClient);
    }

    @Test
    void getInstanceThrowsExceptionForNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> BlobContainerClientFactory.getInstance(null, CONTAINER_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> BlobContainerClientFactory.getInstance("", CONTAINER_NAME));
    }

    @Test
    void getInstanceThrowsExceptionForNullContainerName() {
        assertThrows(IllegalArgumentException.class, () -> BlobContainerClientFactory.getInstance(ENDPOINT, null));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyContainerName() {
        assertThrows(IllegalArgumentException.class, () -> BlobContainerClientFactory.getInstance(ENDPOINT, ""));
    }
}
