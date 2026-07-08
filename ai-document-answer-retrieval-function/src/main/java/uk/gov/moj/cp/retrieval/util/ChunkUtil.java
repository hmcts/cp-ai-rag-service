package uk.gov.moj.cp.retrieval.util;

import static java.lang.String.format;

import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ChunkUtil {

    /** Blob-filename conventions shared by the producer functions and the async result reader. */
    private static final String LLM_ANSWER_WITH_CHUNKS = "llm-answer-with-chunks-%s.json";
    private static final String LLM_INPUT_CHUNKS = "llm-input-chunks-%s.json";

    private ChunkUtil(){
        //util class
    }

    public static String getInputChunksFilename(final UUID transactionId) {
        return format(LLM_INPUT_CHUNKS, transactionId);
    }

    public static String getAnswerWithChunksFilename(final UUID id) {
        return format(LLM_ANSWER_WITH_CHUNKS, id);
    }
    public static List<DocumentChunk> transformChunkEntries(final List<ChunkedEntry> chunkedEntries) {
        if (null == chunkedEntries || chunkedEntries.isEmpty()) {
            return Collections.emptyList();
        }

        return chunkedEntries.stream().map(ce -> {
            final DocumentChunk chunk = new DocumentChunk(ce.documentId(), ce.documentFileName(), ce.pageNumber(), ce.chunk());
            if (null != ce.customMetadata() && !ce.customMetadata().isEmpty()) {
                final List<MetadataFilter> metadataFilters = ce.customMetadata().stream().map(cm -> new MetadataFilter(cm.key(), cm.value())).toList();
                chunk.setCustomMetadata(metadataFilters);
            }
            return chunk;
        }).toList();
    }
}
