package uk.gov.moj.cp.ai.entity;

public class StorageTableColumns {

    public static final String TC_TIMESTAMP = "Timestamp";
    public static final String TC_REASON = "Reason";
    public static final String TC_DOCUMENT_STATUS = "DocumentStatus";
    public static final String TC_DOCUMENT_FILE_NAME = "DocumentFileName";
    public static final String TC_DOCUMENT_ID = "DocumentId";

    // ------------------------
    // Answer Generation by QUEUE processing columns
    // ------------------------
    public static final String TC_TRANSACTION_ID = "TransactionId";
    public static final String TC_USER_QUERY = "UserQuery";
    public static final String TC_QUERY_PROMPT = "QueryPrompt";
    public static final String TC_CHUNKED_ENTRIES = "ChunkedEntries";
    public static final String TC_LLM_RESPONSE = "LlmResponse";
    public static final String TC_ANSWER_STATUS = "AnswerStatus";
    public static final String TC_RESPONSE_GENERATION_DURATION =
            "ResponseGenerationDuration";
}
