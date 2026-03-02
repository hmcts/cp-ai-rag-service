package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;

import uk.gov.moj.cp.ai.FunctionEnvironment;
import uk.gov.moj.cp.ai.client.config.ClientConfiguration;

import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class BlobContainerClientFactoryTest {

    private static final String ENDPOINT = "https://example-endpoint.com";
    private static final String CONTAINER_NAME = "example-container";
    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=https;AccountName=fakeaccount;AccountKey=dummymO9zYKgkcl60VdummyB7KAcKpbdummyO2DMG5dummy6leGWIhkbNghp27M3cL1Clahdummy+dummyC9g==;EndpointSuffix=core.windows.net";

    @Test
    void getInstanceCreatesNewBlobContainerClientForManagedIdentityMode() {
        try (MockedStatic<FunctionEnvironment> envStatic = mockStatic(FunctionEnvironment.class);
             MockedStatic<ClientConfiguration> clientConfigurationMock = mockStatic(ClientConfiguration.class)
        ) {

            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);
            envStatic.when(FunctionEnvironment::get).thenReturn(mockEnv);
            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);

            when(mockStorageConfig.accountConnectionMode()).thenReturn(MANAGED_IDENTITY.name());
            when(mockStorageConfig.blobEndpoint()).thenReturn(ENDPOINT);

            clientConfigurationMock.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));
            clientConfigurationMock.when(ClientConfiguration::createNettyClient).thenReturn(mock(HttpClient.class));

            BlobContainerClient client = BlobContainerClientFactory.getInstance(CONTAINER_NAME);
            assertNotNull(client);
        }
    }

    @Test
    void getInstanceCreatesNewBlobContainerClientForConnectionStringMode() {
        try (MockedStatic<FunctionEnvironment> envStatic = mockStatic(FunctionEnvironment.class);
             MockedStatic<ClientConfiguration> clientConfigurationMock = mockStatic(ClientConfiguration.class)
        ) {
            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);
            envStatic.when(FunctionEnvironment::get).thenReturn(mockEnv);
            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);

            when(mockStorageConfig.accountConnectionMode()).thenReturn("CONNECTION_STRING");
            when(mockStorageConfig.accountName()).thenReturn(CONNECTION_STRING);

            clientConfigurationMock.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));
            clientConfigurationMock.when(ClientConfiguration::createNettyClient).thenReturn(mock(HttpClient.class));

            BlobContainerClient client = BlobContainerClientFactory.getInstance(CONTAINER_NAME);
            assertNotNull(client);
        }
    }

    @Test
    void getInstanceThrowsExceptionForUnsupportedConnectionMode() {
        try (MockedStatic<FunctionEnvironment> envStatic = mockStatic(FunctionEnvironment.class)) {
            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);
            envStatic.when(FunctionEnvironment::get).thenReturn(mockEnv);
            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);
            when(mockStorageConfig.accountConnectionMode()).thenReturn("UNSUPPORTED_MODE");

            assertThrows(IllegalArgumentException.class, () -> BlobContainerClientFactory.getInstance(CONTAINER_NAME));
        }
    }
}
