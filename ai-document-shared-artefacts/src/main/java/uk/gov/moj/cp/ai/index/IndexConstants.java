package uk.gov.moj.cp.ai.index;

public class IndexConstants {

    private IndexConstants() {
    }


    public static final String ID = "id";

    public static final String CHUNK = "chunk";
    public static final String CHUNK_VECTOR = "chunkVector";
    public static final String DOCUMENT_FILE_NAME = "documentFileName";
    public static final String DOCUMENT_ID = "documentId";
    // Additive client-scoping field (MTDI-02). Not used by any production read/write path yet (usage lands in MTDI-04).
    public static final String CLIENT_ID = "clientId";
    public static final String PAGE_NUMBER = "pageNumber";
    public static final String CHUNK_INDEX = "chunkIndex";
    public static final String DOCUMENT_FILE_URL = "documentFileUrl";
    public static final String CUSTOM_METADATA = "customMetadata";

    public static final String IS_ACTIVE = "is_active";
    public static final String FALSE_VALUE = "false";

}
