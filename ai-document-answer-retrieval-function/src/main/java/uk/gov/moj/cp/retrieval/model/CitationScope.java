package uk.gov.moj.cp.retrieval.model;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

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
     * Builds the scope from the retrieved chunks, in retrieval order. The <strong>first</strong>
     * chunk of each document sets its displayed filename through
     * {@link ChunkFormatterUtility#displayedFileName}, exactly as the prompt builder does — even
     * when that name is null, so a later chunk's name can never win (the model never saw it).
     * Pages are the union of the non-null {@code pageNumber}s of the document's chunks.
     *
     * @param chunks the chunk list sent to the model; {@code null} or empty yields {@link #empty()}
     */
    public static CitationScope from(final List<ChunkedEntry> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return EMPTY;
        }

        final Map<String, String> displayedNames = new LinkedHashMap<>();
        final Map<String, SortedSet<Integer>> pagesByDocument = new HashMap<>();
        for (final ChunkedEntry chunk : chunks) {
            final String documentId = chunk.documentId();
            if (isNullOrEmpty(documentId)) {
                continue;
            }
            // containsKey + put rather than putIfAbsent: a null first-chunk name must stay null.
            if (!displayedNames.containsKey(documentId)) {
                displayedNames.put(documentId, ChunkFormatterUtility.displayedFileName(chunk));
            }
            final SortedSet<Integer> pages = pagesByDocument.computeIfAbsent(documentId, id -> new TreeSet<>());
            if (chunk.pageNumber() != null) {
                pages.add(chunk.pageNumber());
            }
        }

        final Map<String, CitableDocument> documents = new HashMap<>();
        displayedNames.forEach((documentId, name) -> {
            final SortedSet<Integer> pages = pagesByDocument.get(documentId);
            if (!isNullOrEmpty(name) && !pages.isEmpty()) {
                documents.put(documentId, new CitableDocument(documentId, name, pages));
            }
        });
        return documents.isEmpty() ? EMPTY : new CitationScope(documents);
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
     * @param pages             the retrieved pages, ascending
     */
    public record CitableDocument(String documentId, String displayedFileName, SortedSet<Integer> pages) {

        public CitableDocument {
            pages = Collections.unmodifiableSortedSet(new TreeSet<>(pages));
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
