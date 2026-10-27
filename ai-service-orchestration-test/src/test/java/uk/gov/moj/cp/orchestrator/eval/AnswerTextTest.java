package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.AnswerText.Citation;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerTextTest {

    private static final String DOC = "a6216640-2750-46b8-9d0a-098e3111f744";

    @Test
    @DisplayName("Rendered citations are parsed into file name, pages and documentId, and stripped from the prose")
    void parsesCitations() {
        final AnswerText answer = AnswerText.of("Peak 9.4℃ ::(Source: [review.pdf], Pages 5-7|5,6,7|documentId=" + DOC
                + "). Serial TL-1 ::(Source: [review.pdf], Pages 9|9|documentId=" + DOC + ").");

        assertEquals(List.of(new Citation("review.pdf", Set.of(5, 6, 7), DOC), new Citation("review.pdf", Set.of(9), DOC)),
                answer.citations());
        assertFalse(answer.prose().contains("Source"));
        assertTrue(answer.prose().contains("Serial TL-1"));
    }

    @Test
    @DisplayName("Without the individual page list, the compressed list is used")
    void fallsBackToCompressedPages() {
        final AnswerText answer = AnswerText.of("x ::(Source: [r.pdf], Pages 3, 8||documentId=" + DOC + ")");

        assertEquals(Set.of(3, 8), answer.citations().get(0).pages());
    }

    @Test
    @DisplayName("Sections split on markdown, bold-only and short colon-terminated heading lines")
    void splitsSections() {
        final String text = "Intro line\n## Findings\nA\n**Response**\nB\nLosses claimed:\nC\nOne sentence that ends with a colon but is far too long to be a heading:\nD";

        assertEquals(4, AnswerText.of(text).sections().size());
    }

    @Test
    @DisplayName("A refusal is the no-data sentinel, or an uncited answer using refusal wording")
    void detectsRefusals() {
        assertTrue(AnswerText.of("No data available matching the query.").isRefusal());
        assertTrue(AnswerText.of("The retrieved documents do not contain information about an insurance policy.").isRefusal());
        assertFalse(AnswerText.of("The policy is not mentioned ::(Source: [r.pdf], Pages 3|3|documentId=" + DOC + ")").isRefusal());
        assertFalse(AnswerText.of("The peak temperature was 9.4°C.").isRefusal());
    }
}
