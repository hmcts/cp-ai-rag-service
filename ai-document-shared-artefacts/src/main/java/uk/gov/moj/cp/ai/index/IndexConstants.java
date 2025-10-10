package uk.gov.moj.cp.ai.index;

public class IndexConstants {

    private IndexConstants() {
    }


    public static final String ID = "id";

    public static final String CHUNK = "chunk";
    public static final String CHUNK_VECTOR = "chunkVector";
    public static final String DOCUMENT_FILE_NAME = "documentFileName";
    public static final String DOCUMENT_ID = "documentId";
    public static final String PAGE_NUMBER = "pageNumber";
    public static final String CHUNK_INDEX = "chunkIndex";
    public static final String DOCUMENT_FILE_URL = "documentFileUrl";

    public static final String CUSTOM_METADATA = "customMetadata";

    public static final int VECTOR_DIMENSIONS = 3072;

    public static final int MIN_CHUNK_LENGTH = 10;

    public static final String DOCUMENT_STATUS = "documentStaus";
    public static final String REASON = "reason";
    public static final String TIMESTAMP = "timestamp";
}
