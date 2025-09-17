package uk.gov.moj.cp.scoring.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ChunkedEntry {

    private static final String CHUNK = "chunk";
    private static final String DOCUMENT_FILE_NAME = "documentFileName";
    private static final String PAGE_NUMBER = "pageNumber";

    @JsonProperty(DOCUMENT_FILE_NAME)
    private String documentFileName;

    @JsonProperty(CHUNK)
    private String chunk;

    @JsonProperty(PAGE_NUMBER)
    private Integer pageNumber;

    @JsonProperty("score")
    private double score;

    public String getDocumentFileName() {
        return documentFileName;
    }

    public void setDocumentFileName(String documentFileName) {
        this.documentFileName = documentFileName;
    }

    public String getChunk() {
        return chunk;
    }

    public void setChunk(String chunk) {
        this.chunk = chunk;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(final Double score) {
        this.score = score;
    }
}
