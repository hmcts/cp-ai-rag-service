package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ChunkFormatterUtility.MATERIAL_NAME_METADATA_KEY;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.model.CitationScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Retrieved-chunk fixtures for citation tests. Every test declares its scope explicitly; deriving
 * it from the answer's own FACT_MAP_JSON would make the tests circular.
 */
public final class CitationScopeFixtures {

    public static final String DOC_A_ID = "aaaaaaaa-0000-4000-8000-000000000001";
    public static final String DOC_A_FILE = "report-a.pdf";

    public static final String DOC_B_ID = "bbbbbbbb-0000-4000-8000-000000000002";
    public static final String DOC_B_FILE = "notes-b.pdf";

    private CitationScopeFixtures() {
    }

    public record Doc(String documentId, String fileName, List<Integer> pages) {

        public Doc {
            pages = List.copyOf(pages);
        }
    }

    public static Doc doc(final String documentId, final String fileName, final int... pages) {
        return new Doc(documentId, fileName, Arrays.stream(pages).boxed().toList());
    }

    /** Document A: {@code report-a.pdf}, pages 2 and 3. */
    public static Doc docA() {
        return doc(DOC_A_ID, DOC_A_FILE, 2, 3);
    }

    /** Document B: {@code notes-b.pdf}, page 5. */
    public static Doc docB() {
        return doc(DOC_B_ID, DOC_B_FILE, 5);
    }

    /** One chunk per page, in argument order. */
    public static List<ChunkedEntry> chunksOf(final Doc... docs) {
        final List<ChunkedEntry> chunks = new ArrayList<>();
        for (final Doc d : docs) {
            for (final Integer page : d.pages()) {
                chunks.add(chunk(d.documentId(), d.fileName(), page, null));
            }
        }
        return chunks;
    }

    public static CitationScope scopeOf(final Doc... docs) {
        return CitationScope.from(chunksOf(docs));
    }

    /** {@code page} and {@code materialName} may be null. */
    public static ChunkedEntry chunk(final String documentId, final String fileName, final Integer page,
                                     final String materialName) {
        final List<KeyValuePair> metadata = materialName == null
                ? List.of()
                : List.of(new KeyValuePair(MATERIAL_NAME_METADATA_KEY, materialName));
        return ChunkedEntry.builder()
                .id(documentId + "-" + page)
                .documentId(documentId)
                .chunk("Content of page " + page)
                .documentFileName(fileName)
                .pageNumber(page)
                .customMetadata(metadata)
                .build();
    }
}
