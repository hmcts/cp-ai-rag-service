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
    // Optional provider for the scoring judge only; when unset the judge follows LLM_CHAT_SERVICE_PROVIDER.
    public static final String JUDGE_LLM_CHAT_SERVICE_PROVIDER = "JUDGE_LLM_CHAT_SERVICE_PROVIDER";
    public static final String EMBEDDING_SERVICE_PROVIDER = "EMBEDDING_SERVICE_PROVIDER";

    // Anthropic Claude through the AI gateway (AnthropicChatService), read for a role whose provider is
    // claude-gateway: the gateway's Messages API base URL and one model per role (no fallback between them).
    public static final String AI_GATEWAY_BASE_URL = "AI_GATEWAY_BASE_URL";
    public static final String AI_GATEWAY_CHAT_MODEL = "AI_GATEWAY_CHAT_MODEL";
    public static final String AI_GATEWAY_JUDGE_MODEL = "AI_GATEWAY_JUDGE_MODEL";
    // The gateway takes an Entra token for its own app registration plus an APIM subscription key; both
    // differ per gateway environment.
    public static final String AI_GATEWAY_TOKEN_SCOPE = "AI_GATEWAY_TOKEN_SCOPE";
    public static final String AI_GATEWAY_SUBSCRIPTION_KEY = "AI_GATEWAY_SUBSCRIPTION_KEY";
    // Local development only: an Azure CLI subscription whose signed-in account has the gateway role,
    // when that is a different account from the one used for the other Azure services. Never set in Azure.
    public static final String AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION = "AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION";
    // Optional Claude request settings: a single sampling temperature (Claude rejects temperature and
    // top_p together), thinking (adaptive | disabled; unset = the model's default) and output effort.
    public static final String LLM_ANTHROPIC_TEMPERATURE = "LLM_ANTHROPIC_TEMPERATURE";
    public static final String LLM_ANTHROPIC_THINKING = "LLM_ANTHROPIC_THINKING";
    public static final String LLM_ANTHROPIC_EFFORT = "LLM_ANTHROPIC_EFFORT";
    public static final String MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB = "MAX_DOCUMENT_UPLOAD_BLOB_SIZE_MIB";

    // How long a queue worker's in-progress idempotency lease stays live before a
    // redelivery may reclaim it. Size above worst-case single-attempt processing time but
    // BELOW visibilityTimeout × (maxDequeueCount − 1), or a crashed leaseholder's lease
    // outlives the retry budget and the row is stuck non-terminal forever.
    public static final String IDEMPOTENCY_LEASE_TTL_SECONDS = "IDEMPOTENCY_LEASE_TTL_SECONDS";

    // Multi-client data isolation. Both default to the safe/off value so behaviour is
    // unchanged until an environment is deliberately cut over.
    // CLIENT_FILTERING_ENABLED: enforcement flag; default "false" (off = today's behaviour).
    public static final String CLIENT_FILTERING_ENABLED = "CLIENT_FILTERING_ENABLED";
    // CLIENT_IDENTITY_HEADER: internal APIM↔function header carrying the caller's clientId; default "X-Client-Id".
    public static final String CLIENT_IDENTITY_HEADER = "CLIENT_IDENTITY_HEADER";

    private SharedSystemVariables() {
        // Prevent instantiation
    }


}
