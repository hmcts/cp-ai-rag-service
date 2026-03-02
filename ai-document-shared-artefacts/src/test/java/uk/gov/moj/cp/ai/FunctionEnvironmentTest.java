package uk.gov.moj.cp.ai;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class FunctionEnvironmentTest {

    @Test
    void get_returnsSingletonInstance() {
        final FunctionEnvironment env1 = FunctionEnvironment.get();
        final FunctionEnvironment env2 = FunctionEnvironment.get();
        assertSame(env1, env2);
    }

    @Test
    void managedIdentity_doesNotLoadConnectionString() {

        try (MockedStatic<FunctionEnvironment> mockFunEnv = mockStatic(FunctionEnvironment.class)) {
            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            mockFunEnv.when(FunctionEnvironment::get).thenReturn(mockEnv);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);
            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);
            when(mockStorageConfig.accountConnectionMode()).thenReturn(MANAGED_IDENTITY.name());

            final FunctionEnvironment env = FunctionEnvironment.get();

            var storage = env.storageConfig();

            assertEquals(MANAGED_IDENTITY.name(), storage.accountConnectionMode());
            assertNull(storage.accountName());
        }
    }

    @Test
    void loadsAllConfigsCorrectly() {
        try (MockedStatic<FunctionEnvironment> mockFunEnv = mockStatic(FunctionEnvironment.class)) {
            final FunctionEnvironment mockEnv = mock(FunctionEnvironment.class);
            mockFunEnv.when(FunctionEnvironment::get).thenReturn(mockEnv);
            final FunctionEnvironment.StorageConfig mockStorageConfig = mock(FunctionEnvironment.StorageConfig.class);
            when(mockEnv.storageConfig()).thenReturn(mockStorageConfig);
            when(mockStorageConfig.blobEndpoint()).thenReturn("https://blob");

            final FunctionEnvironment.QueueConfig mockQueueConfig = mock(FunctionEnvironment.QueueConfig.class);
            when(mockEnv.queueConfig()).thenReturn(mockQueueConfig);
            when(mockQueueConfig.answerScoring()).thenReturn("q1");

            final FunctionEnvironment.TableConfig mockTableConfig = mock(FunctionEnvironment.TableConfig.class);
            when(mockEnv.tableConfig()).thenReturn(mockTableConfig);
            when(mockTableConfig.answerGenerationTable()).thenReturn("answers");

            final FunctionEnvironment.EmbeddingConfig mockEmbeddingConfig = mock(FunctionEnvironment.EmbeddingConfig.class);
            when(mockEnv.embeddingConfig()).thenReturn(mockEmbeddingConfig);
            when(mockEmbeddingConfig.deploymentName()).thenReturn("deploy");

            final FunctionEnvironment env = FunctionEnvironment.get();

            assertThat(env.storageConfig().blobEndpoint(), is("https://blob"));
            assertThat(env.queueConfig().answerScoring(), is("q1"));
            assertThat(env.tableConfig().answerGenerationTable(), is("answers"));
            assertThat(env.embeddingConfig().deploymentName(), is("deploy"));
        }
    }
}