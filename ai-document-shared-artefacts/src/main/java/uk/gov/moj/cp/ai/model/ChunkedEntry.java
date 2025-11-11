package uk.gov.moj.cp.ai.model;

import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_INDEX;
import static uk.gov.moj.cp.ai.index.IndexConstants.CHUNK_VECTOR;
import static uk.gov.moj.cp.ai.index.IndexConstants.CUSTOM_METADATA;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_FILE_URL;
import static uk.gov.moj.cp.ai.index.IndexConstants.DOCUMENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.PAGE_NUMBER;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkedEntry(

        @JsonProperty(ID) String id,
        @JsonProperty(DOCUMENT_ID) String documentId,
        @JsonProperty(CHUNK) String chunk,
        @JsonProperty(CHUNK_VECTOR) List<Float> chunkVector,
        @JsonProperty(DOCUMENT_FILE_NAME) String documentFileName,
        @JsonProperty(PAGE_NUMBER) Integer pageNumber,
        @JsonProperty(CHUNK_INDEX) Integer chunkIndex,
        @JsonProperty(DOCUMENT_FILE_URL) String documentFileUrl,
        @JsonProperty(CUSTOM_METADATA) List<KeyValuePair> customMetadata

) {

    public static class Builder {
        private String id;
        private String documentId;
        private String chunk;
        private List<Float> chunkVector;
        private String documentFileName;
        private Integer pageNumber;
        private Integer chunkIndex;
        private String documentFileUrl;
        private List<KeyValuePair> customMetadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder chunk(String chunk) {
            this.chunk = chunk;
            return this;
        }

        public Builder chunkVector(List<Float> chunkVector) {
            this.chunkVector = chunkVector;
            return this;
        }

        public Builder documentFileName(String documentFileName) {
            this.documentFileName = documentFileName;
            return this;
        }

        public Builder pageNumber(Integer pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        public Builder chunkIndex(Integer chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        public Builder documentFileUrl(String documentFileUrl) {
            this.documentFileUrl = documentFileUrl;
            return this;
        }

        public Builder customMetadata(List<KeyValuePair> customMetadata) {
            this.customMetadata = customMetadata;
            return this;
        }

        public ChunkedEntry build() {
            return new ChunkedEntry(
                    id, documentId, chunk, chunkVector, documentFileName, pageNumber,
                    chunkIndex, documentFileUrl, customMetadata
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
