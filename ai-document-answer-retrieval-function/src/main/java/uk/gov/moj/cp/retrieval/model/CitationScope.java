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
 * Per-answer, immutable view of what the model was shown: documentId → displayed filename plus
 * the set of retrieved pages. Built once from the exact chunk list rendered into
 * {@code <RETRIEVED_DOCUMENTS>}, and the sole source of "retrieved" truth when the citation
 * processor validates the model's {@code FACT_MAP_JSON} entries.
 *
 * <p>A document is <em>citable</em> only when it has a non-blank displayed filename and at least
 * one retrieved page; any other document is left out, so an entry naming it fails validation.
 *
 * @param documents citable documents keyed by their exact documentId
 */
public record CitationScope(Map<String, CitableDocument> documents) {

    private static final CitationScope EMPTY = new CitationScope(Map.of());

    public CitationScope {
        documents = Map.copyOf(documents);
    }

    /** The scope with no citable document: every citation entry validated against it is invalid. */
    public static CitationScope empty() {
        return EMPTY;
    }

    /**
     * Builds the scope from the retrieved chunks, grouped by documentId exactly as the prompt
     * builder groups them: the <strong>first</strong> chunk of each document sets its displayed
     * filename through {@link ChunkFormatterUtility#displayedFileName} (a later chunk's name never
     * wins — the model never saw it), and pages are the non-null {@code pageNumber}s of the
     * document's chunks.
     *
     * @param chunks the chunk list sent to the model; {@code null} or empty yields an empty scope
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

    /** Exact, case-sensitive lookup — no trimming or case folding. {@code null} → empty. */
    public Optional<CitableDocument> document(final String documentId) {
        return documentId == null ? Optional.empty() : Optional.ofNullable(documents.get(documentId));
    }

    /**
     * One citable document as the model was shown it.
     *
     * @param documentId        the retrieved documentId
     * @param displayedFileName the filename shown in the prompt
     * @param pages             the retrieved pages
     */
    public record CitableDocument(String documentId, String displayedFileName, Set<Integer> pages) {

        public CitableDocument {
            pages = Set.copyOf(pages);
        }

        /**
         * True when {@code candidate}, trimmed, equals the displayed filename case-insensitively.
         * {@code null} and blank never match.
         */
        public boolean matchesFileName(final String candidate) {
            if (isNullOrEmpty(candidate) || displayedFileName == null) {
                return false;
            }
            return candidate.trim().equalsIgnoreCase(displayedFileName.trim());
        }
    }
}
