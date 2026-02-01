package uk.gov.moj.cp.ai.model;

import java.util.List;
import java.util.UUID;

public record InputChunksPayload(
        List<ChunkedEntry> chunkedEntries,
        UUID transactionId
) {
}
