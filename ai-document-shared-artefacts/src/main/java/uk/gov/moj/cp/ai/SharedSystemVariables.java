package uk.gov.moj.cp.ai;

public class SharedSystemVariables {

    // Identity-based binding prefix for the Functions storage triggers/outputs.
    // The Functions host resolves the matching `__accountName` app setting and authenticates via managed identity.
    public static final String AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING = "AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING";
    public static final String AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT = "AI_RAG_SERVICE_BLOB_STORAGE_ENDPOINT";
    public static final String AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT = "AI_RAG_SERVICE_TABLE_STORAGE_ENDPOINT";

    public static final String STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING = "STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING";
    public static final String STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION = "STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION";

    public static final String STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION = "STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION";

    public static final String STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION = "STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION";
    public static final String STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME = "STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME";

    public static final String STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD = "STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_DOCUMENT_UPLOAD";
    public static final String STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS = "STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS";
    public static final String STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS = "STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS";

    public static final String AZURE_EMBEDDING_SERVICE_ENDPOINT = "AZURE_EMBEDDING_SERVICE_ENDPOINT";
    public static final String AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME = "AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME";

    public static final String AZURE_SEARCH_SERVICE_ENDPOINT = "AZURE_SEARCH_SERVICE_ENDPOINT";
    public static final String AZURE_SEARCH_SERVICE_INDEX_NAME = "AZURE_SEARCH_SERVICE_INDEX_NAME";

    public static final String LLM_MODEL_RESPONSE_MAX_TOKENS = "LLM_MODEL_RESPONSE_MAX_TOKENS";
    public static final String LLM_MODEL_RESPONSE_VERBOSITY = "LLM_MODEL_RESPONSE_VERBOSITY";
    public static final String LLM_CHAT_SERVICE_PROVIDER = "LLM_CHAT_SERVICE_PROVIDER";
    public static final String MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB = "MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB";

    // How long a queue worker's in-progress idempotency lease stays live before a
    // redelivery may reclaim it. Size above worst-case single-attempt processing time but
    // BELOW visibilityTimeout × (maxDequeueCount − 1), or a crashed leaseholder's lease
    // outlives the retry budget and the row is stuck non-terminal forever.
    public static final String IDEMPOTENCY_LEASE_TTL_SECONDS = "IDEMPOTENCY_LEASE_TTL_SECONDS";

    // Multi-client data isolation (MTDI-01). Both default to the safe/off value so behaviour is
    // unchanged until an environment is deliberately cut over.
    // CLIENT_FILTERING_ENABLED: enforcement flag (FR-3); default "false" (off = today's behaviour).
    public static final String CLIENT_FILTERING_ENABLED = "CLIENT_FILTERING_ENABLED";
    // CLIENT_IDENTITY_HEADER: internal APIM↔function header carrying the caller's clientId; default "X-Client-Id" (D4).
    public static final String CLIENT_IDENTITY_HEADER = "CLIENT_IDENTITY_HEADER";

    private SharedSystemVariables() {
        // Prevent instantiation
    }


}
