package uk.gov.moj.cp.retrieval.model;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the model was shown for one answer: each citable document's displayed filename and
 * retrieved pages. A document is citable only with a non-blank displayed filename and at least
 * one page; others are left out, so an entry naming them fails validation.
 */
public record CitationScope(Map<String, CitableDocument> documents) {

    private static final CitationScope EMPTY = new CitationScope(Map.of());

    public CitationScope {
        documents = Map.copyOf(documents);
    }

    public static CitationScope empty() {
        return EMPTY;
    }

    /**
     * Groups the chunks sent to the model exactly as the prompt builder does: the first chunk of
     * a document names it via {@link ChunkFormatterUtility#displayedFileName}, because that is the
     * only name the model saw.
     */
    public static CitationScope from(final List<ChunkedEntry> chunks) {
        if (chunks == null) {
            return EMPTY;
        }
        final Map<String, CitableDocument> documents = new HashMap<>();
        chunks.stream()
                .filter(chunk -> !isNullOrEmpty(chunk.documentId()))
                .collect(groupingBy(ChunkedEntry::documentId))
                .forEach((documentId, documentChunks) -> {
                    final String name = ChunkFormatterUtility.displayedFileName(documentChunks.getFirst());
                    final Set<Integer> pages = documentChunks.stream()
                            .map(ChunkedEntry::pageNumber)
                            .filter(Objects::nonNull)
                            .collect(toSet());
                    if (!isNullOrEmpty(name) && !pages.isEmpty()) {
                        documents.put(documentId, new CitableDocument(documentId, name, pages));
                    }
                });
        return new CitationScope(documents);
    }

    /** Exact lookup: no trimming or case folding. */
    public Optional<CitableDocument> document(final String documentId) {
        return documentId == null ? Optional.empty() : Optional.ofNullable(documents.get(documentId));
    }

    public record CitableDocument(String documentId, String displayedFileName, Set<Integer> pages) {

        public CitableDocument {
            pages = Set.copyOf(pages);
        }

        /** Trimmed and case-insensitive; blank never matches. */
        public boolean matchesFileName(final String candidate) {
            if (isNullOrEmpty(candidate) || displayedFileName == null) {
                return false;
            }
            return candidate.trim().equalsIgnoreCase(displayedFileName.trim());
        }
    }
}
