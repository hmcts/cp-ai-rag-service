package uk.gov.moj.cp.ingestion.service;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.config.ChunkingConfig;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.model.PageChunk;

import java.util.ArrayList;
import java.util.List;

import com.azure.ai.formrecognizer.documentanalysis.models.AnalyzeResult;
import com.azure.ai.formrecognizer.documentanalysis.models.DocumentLine;
import com.azure.ai.formrecognizer.documentanalysis.models.DocumentPage;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentChunkingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentChunkingService.class);

    public List<PageChunk> chunkDocument(AnalyzeResult result,
                                         QueueIngestionMetadata queueMetadata) throws DocumentProcessingException {
        return chunkDocument(result, queueMetadata, ChunkingConfig.getDefault());
    }

    public List<PageChunk> chunkDocument(AnalyzeResult result,
                                         QueueIngestionMetadata queueMetadata,
                                         ChunkingConfig config) throws DocumentProcessingException {

        LOGGER.info("Starting document chunking for: {}", queueMetadata.documentName());

        try {
            List<PageChunk> finalChunks = new ArrayList<>();
            int pageIndex = 1;

            DocumentSplitter splitter = recursive(config.chunkSize(), config.chunkOverlap());

            for (DocumentPage page : result.getPages()) {
                List<PageChunk> pageChunks = processPage(page, pageIndex, queueMetadata, splitter, config);
                finalChunks.addAll(pageChunks);
                pageIndex++;
            }

            LOGGER.info("Document chunking completed: {} chunks created", finalChunks.size());
            return finalChunks;

        } catch (Exception e) {
            String errorMsg = "Failed to chunk document: " + e.getMessage();
            LOGGER.error(errorMsg, e);
            throw new DocumentProcessingException(errorMsg, e);
        }
    }

    private List<PageChunk> processPage(DocumentPage page,
                                        int pageIndex,
                                        QueueIngestionMetadata queueMetadata,
                                        DocumentSplitter splitter,
                                        ChunkingConfig config) {
        List<PageChunk> pageChunks = new ArrayList<>();

        List<String> lines = extractTextFromPage(page);
        String fullText = String.join(" ", lines);

        if (fullText.trim().isEmpty()) {
            LOGGER.warn("Page {} has no extractable text, skipping", pageIndex);
            return pageChunks;
        }

        try {
            Document document = Document.from(fullText);
            List<TextSegment> segments = splitter.split(document);

            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                String chunkContent = segment.text().trim();

                if (isValidChunk(chunkContent)) {
                    PageChunk chunk = createPageChunk(pageIndex, chunkContent, queueMetadata, config, i);
                    pageChunks.add(chunk);

                    LOGGER.info("Created chunk {} for page {} with {} characters",
                            i + 1, pageIndex, chunkContent.length());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error processing page {}: {}", pageIndex, e.getMessage());
        }

        return pageChunks;
    }

    private List<String> extractTextFromPage(DocumentPage page) {
        List<String> lines = new ArrayList<>();
        if (page.getLines() != null) {
            for (DocumentLine line : page.getLines()) {
                if (line != null && line.getContent() != null) {
                    lines.add(line.getContent());
                }
            }
        }
        return lines;
    }


    private boolean isValidChunk(String chunkContent) {
        return chunkContent.length() > 10;
    }

    private PageChunk createPageChunk(int pageIndex,
                                      String chunkContent,
                                      QueueIngestionMetadata queueMetadata,
                                      ChunkingConfig config,
                                      int chunkIndex) {

        PageChunk chunk = new PageChunk(pageIndex, chunkContent);

        chunk.setDocumentId(queueMetadata.documentId());
        chunk.setFileName(queueMetadata.documentName());
        chunk.setOriginalFileUrl(queueMetadata.blobUrl());
        chunk.setChunkIndex(chunkIndex);
        chunk.setPageNumber(pageIndex);

        // additional chunking info
        chunk.setChunkingStrategy(config.chunkingStrategy());
        chunk.setChunkSize(chunkContent.length());
        chunk.setChunkOverlap(config.chunkOverlap());

        queueMetadata.metadata().forEach(chunk::addCustomMetadata);


        LOGGER.debug("Created chunk [{}] for page {} of document {}",
                chunkIndex + 1, pageIndex, queueMetadata.documentName());

        return chunk;
    }
}