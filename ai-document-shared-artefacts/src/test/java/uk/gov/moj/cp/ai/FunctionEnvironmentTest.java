package uk.gov.moj.cp.ai;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_EMBEDDING_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_SEARCH_SERVICE_INDEX_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.util.EnvVarUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class FunctionEnvironmentTest {

    @BeforeEach
    void setup() {
        setupEnvVariables();
    }

    @Test
    void get_returnsSingletonInstance() {
        final FunctionEnvironment env1 = FunctionEnvironment.get();
        final FunctionEnvironment env2 = FunctionEnvironment.get();
        assertSame(env1, env2);
    }

    @Test
    void managedIdentity_doesNotLoadConnectionString() {
        final FunctionEnvironment env = FunctionEnvironment.get();

        var storage = env.storageConfig();

        assertEquals(MANAGED_IDENTITY.name(), storage.accountConnectionMode());
        assertNull(storage.accountName());
    }

    @Test
    void loadsAllConfigsCorrectly() {
        final FunctionEnvironment env = FunctionEnvironment.get();

        assertNotNull(env.storageConfig());
        assertThat(env.storageConfig().blobEndpoint(), is("https://blob"));
        assertNotNull(env.queueConfig());
        assertThat(env.queueConfig().answerScoring(), is("q1"));
        assertNotNull(env.searchConfig());
        assertThat(env.searchConfig().serviceEndpoint(), is("https://search"));
        assertNotNull(env.embeddingConfig());
        assertThat(env.embeddingConfig().deploymentName(), is("deploy"));
        assertNotNull(env.tableConfig());
        assertThat(env.tableConfig().documentIngestionOutcomeTable(), is("t2"));
    }

    private static void setupEnvVariables() {

        try (MockedStatic<EnvVarUtil> env = mockStatic(EnvVarUtil.class)) {
            env.when(() -> getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name()))
                    .thenReturn(MANAGED_IDENTITY.name());

            env.when(() -> getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT)).thenReturn("https://blob");
            env.when(() -> getRequiredEnv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT)).thenReturn("https://table");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME)).thenReturn("landing");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS)).thenReturn("eval");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS)).thenReturn("inputs");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING)).thenReturn("q1");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION)).thenReturn("q2");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION)).thenReturn("q3");

            env.when(() -> getRequiredEnv(AZURE_SEARCH_SERVICE_ENDPOINT)).thenReturn("https://search");
            env.when(() -> getRequiredEnv(AZURE_SEARCH_SERVICE_INDEX_NAME)).thenReturn("index");
            env.when(() -> getRequiredEnv(AZURE_EMBEDDING_SERVICE_ENDPOINT)).thenReturn("https://embed");

            env.when(() -> getRequiredEnv(AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME)).thenReturn("deploy");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION)).thenReturn("t1");
            env.when(() -> getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME)).thenReturn("t2");


            FunctionEnvironment fe = FunctionEnvironment.get();
            assertNotNull(fe);
        }
    }
}