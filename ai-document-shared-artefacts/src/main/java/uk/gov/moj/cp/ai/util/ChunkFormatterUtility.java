package uk.gov.moj.cp.ai.util;

import static java.util.stream.Collectors.groupingBy;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ChunkFormatterUtility {

    public String buildChunkContext(List<ChunkedEntry> chunkedEntries) {

        if (chunkedEntries == null || chunkedEntries.isEmpty()) {
            return ("<RETRIEVED_DOCUMENTS></RETRIEVED_DOCUMENTS>");
        }

        StringBuilder sb = new StringBuilder("<RETRIEVED_DOCUMENTS>\n");

        Map<String, List<ChunkedEntry>> entriesByDocumentId = chunkedEntries.stream()
                .collect(groupingBy(ChunkedEntry::documentId));

        for (Map.Entry<String, List<ChunkedEntry>> entriesPerDocument : entriesByDocumentId.entrySet()) {
            final String documentId = entriesPerDocument.getKey();
            final ChunkedEntry firstChunkForDocumentId = entriesPerDocument.getValue().getFirst();
            String documentFileName = extractMaterialName(firstChunkForDocumentId)
                    .orElse(firstChunkForDocumentId.documentFileName());
            sb.append("<DOCUMENT DOCUMENT_ID=\"").append(documentId).append("\" DOCUMENT_FILENAME=\"").append(documentFileName).append("\">\n");
            for (ChunkedEntry entry : entriesPerDocument.getValue()) {
                final String pageNumber = null != entry.pageNumber() ? entry.pageNumber().toString() : "";

                // CHUNK_ID intentionally omitted: nothing downstream consumes a chunk id, and exposing
                // the per-chunk GUID led models (notably gpt-5.1) to misuse it as the documentId and to
                // derive citationIds from its hex prefix. Citations are page-level (DOCUMENT_ID + page).
                sb.append("<DATA>\n");
                sb.append("<PAGE_NUMBER>").append(pageNumber).append("</PAGE_NUMBER>\n");
                sb.append("<DOCUMENT_CONTENT>").append(entry.chunk()).append("</DOCUMENT_CONTENT>\n");
                sb.append("</DATA>\n");
            }
            sb.append("</DOCUMENT>\n");
        }
        sb.append("</RETRIEVED_DOCUMENTS>");
        return sb.toString();
    }

    private Optional<String> extractMaterialName(ChunkedEntry entry) {
        if (entry.customMetadata() == null || entry.customMetadata().isEmpty()) {
            return Optional.empty();
        }

        return entry.customMetadata().stream()
                .filter(pair -> "material_name".equals(pair.key()))
                .map(KeyValuePair::value)
                .filter(value -> !isNullOrEmpty(value))
                .findFirst();
    }
}
