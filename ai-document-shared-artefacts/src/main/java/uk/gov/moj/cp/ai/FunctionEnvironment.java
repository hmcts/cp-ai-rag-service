package uk.gov.moj.cp.ai;


import static java.util.Objects.isNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
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
import static uk.gov.moj.cp.ai.client.ConnectionMode.CONNECTION_STRING;
import static uk.gov.moj.cp.ai.client.ConnectionMode.MANAGED_IDENTITY;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

public final class FunctionEnvironment {

    private StorageConfig storageConfig;
    private QueueConfig queueConfig;
    private SearchConfig searchConfig;
    private EmbeddingConfig embeddingConfig;
    private TableConfig tableConfig;

    private static class Holder {
        private static FunctionEnvironment INSTANCE;

        public static FunctionEnvironment get() {
            if (isNull(INSTANCE)) {
                INSTANCE = new FunctionEnvironment();
            }
            return INSTANCE;
        }
    }

    public static FunctionEnvironment get() {
        return Holder.get();
    }

    public StorageConfig storageConfig() {
        if (storageConfig == null) {
            storageConfig = StorageConfig.load();
        }
        return storageConfig;
    }

    public QueueConfig queueConfig() {
        if (queueConfig == null) {
            queueConfig = QueueConfig.load();
        }
        return queueConfig;
    }

    public SearchConfig searchConfig() {
        if (searchConfig == null) {
            searchConfig = SearchConfig.load();
        }
        return searchConfig;
    }

    public EmbeddingConfig embeddingConfig() {
        if (embeddingConfig == null) {
            embeddingConfig = EmbeddingConfig.load();
        }
        return embeddingConfig;
    }

    public TableConfig tableConfig() {
        if (tableConfig == null) {
            tableConfig = TableConfig.load();
        }
        return tableConfig;
    }

    public record StorageConfig(
            String accountConnectionMode,
            String accountName,
            String blobEndpoint,
            String tableEndpoint,
            String documentLandingContainer,
            String evalPayloadsContainer,
            String inputsContainer
    ) {
        private static StorageConfig load() {
            final String connectionMode = getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_MODE, MANAGED_IDENTITY.name());

            return new StorageConfig(
                    connectionMode,
                    connectionMode.equals(CONNECTION_STRING.name()) ? getRequiredEnv(AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING) : null,
                    getRequiredEnv(AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT),
                    getRequiredEnv(AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT),
                    getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME),
                    getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS),
                    getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS)
            );
        }
    }

    public record QueueConfig(
            String answerScoring,
            String answerGeneration,
            String documentIngestion
    ) {
        private static QueueConfig load() {
            return new QueueConfig(
                    getRequiredEnv(STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING),
                    getRequiredEnv(STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION),
                    getRequiredEnv(STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION));
        }
    }

    public record SearchConfig(
            String serviceEndpoint,
            String serviceIndexName
    ) {
        private static SearchConfig load() {
            return new SearchConfig(
                    getRequiredEnv(AZURE_SEARCH_SERVICE_ENDPOINT),
                    getRequiredEnv(AZURE_SEARCH_SERVICE_INDEX_NAME)
            );
        }
    }

    public record EmbeddingConfig(
            String serviceEndpoint,
            String deploymentName
    ) {
        private static EmbeddingConfig load() {
            return new EmbeddingConfig(
                    getRequiredEnv(AZURE_EMBEDDING_SERVICE_ENDPOINT),
                    getRequiredEnv(AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME)
            );
        }
    }

    public record TableConfig(
            String answerGenerationTable,
            String documentIngestionOutcomeTable
    ) {
        private static TableConfig load() {
            return new TableConfig(
                    getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION),
                    getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME)
            );
        }
    }
}
