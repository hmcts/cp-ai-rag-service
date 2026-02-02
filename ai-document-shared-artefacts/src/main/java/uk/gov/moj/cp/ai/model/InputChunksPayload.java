package uk.gov.moj.cp.ai.model;

import java.util.List;

public record InputChunksPayload(
        List<ChunkedEntry> chunkedEntries
) {
}
