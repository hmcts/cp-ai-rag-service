package uk.gov.moj.cp.ingestion.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PageChunk {

    private String id;
    private String chunk;
    private List<Double> contentVector;
    private String fileName;
    private Integer pageNumber;
    private Integer chunkIndex;
    private String originalFileUrl;
    private String businessDomain;
    private String documentId;

    private List<CustomMetadata> customMetadata = new ArrayList<>();
    private double confidenceScore = 0.95;
    private String chunkingStrategy;
    private int chunkSize;
    private int chunkOverlap;
    private LocalDateTime ingestionTime = LocalDateTime.now();

    public PageChunk(int pageNumber, String chunkContent) {
        this.id = UUID.randomUUID().toString();
        this.pageNumber = pageNumber;
        this.chunk = chunkContent;
    }

    public void addCustomMetadata(String key, String value) {
        if (key != null && value != null) {
            this.customMetadata.add(new CustomMetadata(key, value));
        }
    }

    public String getCustomMetadataValue(String key) {
        return customMetadata.stream()
                .filter(m -> key.equals(m.getKey()))
                .map(CustomMetadata::getValue)
                .findFirst()
                .orElse(null);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChunk() {
        return chunk;
    }

    public void setChunk(String chunk) {
        this.chunk = chunk;
    }

    public List<Double> getContentVector() {
        return contentVector;
    }

    public void setContentVector(List<Double> contentVector) {
        this.contentVector = contentVector;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getOriginalFileUrl() {
        return originalFileUrl;
    }

    public void setOriginalFileUrl(String originalFileUrl) {
        this.originalFileUrl = originalFileUrl;
    }

    public String getBusinessDomain() {
        return businessDomain;
    }

    public void setBusinessDomain(String businessDomain) {
        this.businessDomain = businessDomain;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public List<CustomMetadata> getCustomMetadata() {
        return customMetadata;
    }

    public void setCustomMetadata(List<CustomMetadata> customMetadata) {
        this.customMetadata = customMetadata;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getChunkingStrategy() {
        return chunkingStrategy;
    }

    public void setChunkingStrategy(String chunkingStrategy) {
        this.chunkingStrategy = chunkingStrategy;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public LocalDateTime getIngestionTime() {
        return ingestionTime;
    }

    public void setIngestionTime(LocalDateTime ingestionTime) {
        this.ingestionTime = ingestionTime;
    }


    @Override
    public String toString() {
        return "PageChunk{" +
               "documentId='" + documentId + '\'' +
               ", pageNumber=" + pageNumber +
               ", chunkIndex=" + chunkIndex +
               ", fileName='" + fileName + '\'' +
               ", chunkLength=" + (chunk != null ? chunk.length() : 0) +
               ", hasVector=" + (contentVector != null && !contentVector.isEmpty()) +
               '}';
    }
}