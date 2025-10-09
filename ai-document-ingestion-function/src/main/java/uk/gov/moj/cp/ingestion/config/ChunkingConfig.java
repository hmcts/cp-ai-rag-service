package uk.gov.moj.cp.ingestion.config;


public record ChunkingConfig(int chunkSize, int chunkOverlap, String chunkingStrategy) {

    public static ChunkingConfig getDefault() {
        return new ChunkingConfig(4000, 500, "LangChain4j-Recursive");
    }
}

