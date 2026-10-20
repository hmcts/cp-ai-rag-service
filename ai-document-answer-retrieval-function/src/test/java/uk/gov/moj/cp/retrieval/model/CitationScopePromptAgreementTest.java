package uk.gov.moj.cp.retrieval.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunk;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Pins the agreement between the prompt builder and the citation validator: for every document,
 * the {@code DOCUMENT_FILENAME} the model was shown in {@code <RETRIEVED_DOCUMENTS>} must equal
 * the displayed name {@link CitationScope} validates against. Parses the real
 * {@link ChunkFormatterUtility#buildChunkContext} output rather than re-stating the rule.
 */
class CitationScopePromptAgreementTest {

    private static final Pattern DOCUMENT_TAG =
            Pattern.compile("<DOCUMENT DOCUMENT_ID=\"([^\"]{1,80})\" DOCUMENT_FILENAME=\"([^\"]{0,200})\">");

    @Test
    void materialNamePresent() {
        assertPromptAndScopeAgree(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "summary-a"),
                chunk(DOC_B_ID, DOC_B_FILE, 5, null)));
    }

    @Test
    void materialNameBlank() {
        assertPromptAndScopeAgree(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "  "),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "")));
    }

    @Test
    void materialNameAbsent() {
        assertPromptAndScopeAgree(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_B_ID, DOC_B_FILE, 5, null)));
    }

    @Test
    void materialNameDifferingAcrossChunks_firstChunkWinsOnBothSides() {
        assertPromptAndScopeAgree(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, "summary-a"),
                chunk(DOC_B_ID, DOC_B_FILE, 5, null),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "summary-a-later"),
                chunk(DOC_B_ID, DOC_B_FILE, 5, "summary-b-later")));
    }

    @Test
    void materialNameOnlyOnALaterChunk_isIgnoredOnBothSides() {
        assertPromptAndScopeAgree(List.of(
                chunk(DOC_A_ID, DOC_A_FILE, 2, null),
                chunk(DOC_A_ID, DOC_A_FILE, 3, "summary-a")));
    }

    private static void assertPromptAndScopeAgree(final List<ChunkedEntry> chunks) {
        final Map<String, String> shownToModel = parseDocumentFilenames(
                new ChunkFormatterUtility().buildChunkContext(chunks));
        final CitationScope scope = CitationScope.from(chunks);

        assertEquals(shownToModel.keySet(), scope.documents().keySet());
        shownToModel.forEach((documentId, shownName) ->
                assertEquals(shownName, scope.document(documentId).orElseThrow().displayedFileName(),
                        "displayed filename for " + documentId));
    }

    private static Map<String, String> parseDocumentFilenames(final String context) {
        final Map<String, String> byDocumentId = new HashMap<>();
        final Matcher m = DOCUMENT_TAG.matcher(context);
        while (m.find()) {
            byDocumentId.put(m.group(1), m.group(2));
        }
        return byDocumentId;
    }
}
