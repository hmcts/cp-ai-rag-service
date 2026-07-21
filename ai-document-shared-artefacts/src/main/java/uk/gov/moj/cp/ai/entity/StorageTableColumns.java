package uk.gov.moj.cp.ai.entity;

public class StorageTableColumns {

    public static final String TC_TIMESTAMP = "Timestamp";
    public static final String TC_REASON = "Reason";
    public static final String TC_DOCUMENT_STATUS = "DocumentStatus";
    public static final String TC_DOCUMENT_METADATA = "DocumentMetadata";
    public static final String TC_DOCUMENT_SUPERSEDED_DOCUMENTS = "SupersededDocuments";
    public static final String TC_DOCUMENT_FILE_NAME = "DocumentFileName";
    public static final String TC_DOCUMENT_ID = "DocumentId";
    // Additive client-scoping column (MTDI-02). Not used by any table read/write path yet (usage lands in MTDI-03).
    public static final String TC_CLIENT_ID = "ClientId";

    // ------------------------
    // Answer Generation by QUEUE processing columns
    // ------------------------
    public static final String TC_TRANSACTION_ID = "TransactionId";
    public static final String TC_USER_QUERY = "UserQuery";
    public static final String TC_QUERY_PROMPT = "QueryPrompt";
    public static final String TC_CHUNKED_ENTRIES_FILE = "ChunkedEntriesFile";
    public static final String TC_LLM_RESPONSE = "LlmResponse";
    public static final String TC_ANSWER_STATUS = "AnswerStatus";
    public static final String TC_RESPONSE_GENERATION_TIME = "ResponseGenerationTime";
    public static final String TC_RESPONSE_GENERATION_DURATION = "ResponseGenerationDuration";
    public static final String TC_RESPONSE_GROUNDEDNESS_SCORE = "GroundednessScore";

    // ------------------------
    // Idempotency lease columns (internal — not part of the public API), on BOTH status tables
    // ------------------------
    public static final String TC_LEASE_OWNER = "LeaseOwner";
    public static final String TC_LEASE_EXPIRES_AT = "LeaseExpiresAt";

    private StorageTableColumns(){
        //constants class
    }
}
