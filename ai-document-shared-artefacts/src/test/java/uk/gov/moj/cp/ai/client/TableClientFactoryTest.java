package uk.gov.moj.cp.ai.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.client.config.ClientConfiguration;
import uk.gov.moj.cp.ai.util.EnvVarUtil;

import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.data.tables.TableClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class TableClientFactoryTest {

    private static final String TABLE_NAME = "example-table";
    private static final String DIFFERENT_TABLE_NAME = "different-table";
    private static final String TABLE_STORAGE_ENDPOINT = "https://example.table.core.windows.net/";
    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=https;AccountName=fakeaccount;AccountKey=dummymO9zYKgkcl60VdummyB7KAcKpbdummyO2DMG5dummy6leGWIhkbNghp27M3cL1Clahdummy+dummyC9g==;EndpointSuffix=core.windows.net";

    @Test
    void getInstanceCreatesNewTableClientUsingManagedIdentityWhenNotInCache() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> mockedClientConfiguration = mockStatic(ClientConfiguration.class)
        ) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(MANAGED_IDENTITY.name());
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT))
                    .thenReturn(TABLE_STORAGE_ENDPOINT);
            mockedClientConfiguration.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));

            TableClient client = TableClientFactory.getInstance(TABLE_NAME);

            assertNotNull(client);
        }
    }

    @Test
    void getInstanceCreatesNewTableClientUsingConnectionStringWhenNotInCache() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> mockedClientConfiguration = mockStatic(ClientConfiguration.class)
        ) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(ConnectionMode.CONNECTION_STRING.name());
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING))
                    .thenReturn(CONNECTION_STRING);
            mockedClientConfiguration.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));

            TableClient client = TableClientFactory.getInstance(TABLE_NAME);

            assertNotNull(client);
        }
    }

    @Test
    void getInstanceReturnsCachedTableClientUsingManagedIdentityForSameEndpointAndTableName() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> mockedClientConfiguration = mockStatic(ClientConfiguration.class)
        ) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(MANAGED_IDENTITY.name());
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT))
                    .thenReturn(TABLE_STORAGE_ENDPOINT);
            mockedClientConfiguration.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));

            TableClient firstClient = TableClientFactory.getInstance(TABLE_NAME);
            TableClient secondClient = TableClientFactory.getInstance(TABLE_NAME);

            assertSame(firstClient, secondClient);
        }
    }

    @Test
    void getInstanceReturnsCachedTableClientUsingConnectionStringForSameEndpointAndTableName() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> mockedClientConfiguration = mockStatic(ClientConfiguration.class)
        ) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(ConnectionMode.CONNECTION_STRING.name());
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING))
                    .thenReturn(CONNECTION_STRING);
            mockedClientConfiguration.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));

            TableClient firstClient = TableClientFactory.getInstance(TABLE_NAME);
            TableClient secondClient = TableClientFactory.getInstance(TABLE_NAME);

            assertSame(firstClient, secondClient);
        }
    }

    @Test
    void getInstanceReturnsNewTableClientForDifferentEndpointOrTableName() {
        try (MockedStatic<EnvVarUtil> mockedEnvVarUtil = mockStatic(EnvVarUtil.class);
             MockedStatic<ClientConfiguration> mockedClientConfiguration = mockStatic(ClientConfiguration.class)
        ) {
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(MANAGED_IDENTITY.name());
            mockedEnvVarUtil.when(() -> getRequiredEnv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT))
                    .thenReturn(TABLE_STORAGE_ENDPOINT);
            mockedClientConfiguration.when(ClientConfiguration::getRetryOptions).thenReturn(new RetryOptions(new ExponentialBackoffOptions()));

            TableClient firstClient = TableClientFactory.getInstance(TABLE_NAME);
            TableClient secondClient = TableClientFactory.getInstance(DIFFERENT_TABLE_NAME);
            assertNotSame(firstClient, secondClient);
        }
    }

    @Test
    void getInstanceThrowsExceptionForNullTableName() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance(null));
    }

    @Test
    void getInstanceThrowsExceptionForEmptyTableName() {
        assertThrows(IllegalArgumentException.class, () -> TableClientFactory.getInstance(""));
    }
}
