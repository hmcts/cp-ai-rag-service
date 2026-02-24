package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import uk.gov.moj.cp.ai.client.config.ClientConfiguration;
import uk.gov.moj.cp.ai.util.EnvVarUtil;

import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class BlobServiceClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String CONTAINER_NAME = "example-container";
    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=https;AccountName=fakeaccount;AccountKey=dummymO9zYKgkcl60VdummyB7KAcKpbdummyO2DMG5dummy6leGWIhkbNghp27M3cL1Clahdummy+dummyC9g==;EndpointSuffix=core.windows.net";

    @Test
    void getInstanceCreatesNewBlobServiceClientForManagedIdentityMode() {
        try (MockedStatic<EnvVarUtil> envVarUtilMock = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> clientConfigurationMock = mockStatic(ClientConfiguration.class)
        ) {

            envVarUtilMock.when(() -> EnvVarUtil.getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE", "MANAGED_IDENTITY"))
                    .thenReturn("MANAGED_IDENTITY");
            envVarUtilMock.when(() -> EnvVarUtil.getRequiredEnv("AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT"))
                    .thenReturn(ENDPOINT);
            clientConfigurationMock.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));
            clientConfigurationMock.when(ClientConfiguration::createNettyClient).thenReturn(mock(HttpClient.class));

            BlobServiceClient serviceClient = BlobServiceClientFactory.getInstance(CONTAINER_NAME);
            assertNotNull(serviceClient);
        }
    }

    @Test
    void getInstanceCreatesNewBlobServiceClientForConnectionStringMode() {
        try (MockedStatic<EnvVarUtil> envVarUtilMock = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> clientConfigurationMock = mockStatic(ClientConfiguration.class)
        ) {

            envVarUtilMock.when(() -> EnvVarUtil.getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE", "MANAGED_IDENTITY"))
                    .thenReturn("CONNECTION_STRING");
            envVarUtilMock.when(() -> EnvVarUtil.getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING"))
                    .thenReturn(CONNECTION_STRING);
            clientConfigurationMock.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));
            clientConfigurationMock.when(ClientConfiguration::createNettyClient).thenReturn(mock(HttpClient.class));

            BlobServiceClient serviceClient = BlobServiceClientFactory.getInstance(CONTAINER_NAME);
            assertNotNull(serviceClient);
        }
    }

    @Test
    void getInstanceThrowsExceptionForUnsupportedConnectionMode() {
        try (MockedStatic<EnvVarUtil> envVarUtilMock = mockStatic(EnvVarUtil.class)) {

            envVarUtilMock.when(() -> EnvVarUtil.getRequiredEnv("AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE", "MANAGED_IDENTITY"))
                    .thenReturn("UNSUPPORTED_MODE");

            assertThrows(IllegalArgumentException.class, () -> BlobServiceClientFactory.getInstance(CONTAINER_NAME));
        }
    }

}