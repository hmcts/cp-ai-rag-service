package uk.gov.moj.cp.ai.model;

import uk.gov.moj.cp.ai.index.IndexConstants;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChunkedEntry(

        @JsonProperty(IndexConstants.ID) String id,
        @JsonProperty(IndexConstants.CHUNK) String chunk,
        @JsonProperty(IndexConstants.DOCUMENT_FILE_NAME) String documentFileName,
        @JsonProperty(IndexConstants.PAGE_NUMBER) Integer pageNumber,
        @JsonProperty(IndexConstants.DOCUMENT_ID) String documentId

) {
}
