package uk.gov.moj.cp.retrieval.util;

import uk.gov.hmcts.cp.openapi.model.DocumentChunk;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.Collections;
import java.util.List;

public class ChunkUtil {

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
