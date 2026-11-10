package uk.gov.moj.cp.orchestrator.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.orchestrator.eval.AnswerText.Citation;

import java.util.Arrays;
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
    @DisplayName("Sections split on markdown, bold-only and short colon-terminated heading lines; an intro before the first heading is not one")
    void splitsSections() {
        final String text = "Intro line\n## Findings\nA\n**Response**\nB\nLosses claimed:\nC\nOne sentence that ends with a colon but is far too long to be a heading:\nD";

        assertEquals(List.of("## Findings\nA", "**Response**\nB",
                "Losses claimed:\nC\nOne sentence that ends with a colon but is far too long to be a heading:\nD"), AnswerText.of(text).sections());
        assertEquals(List.of("A line\nAnother"), AnswerText.of("A line\nAnother").sections());
    }

    @Test
    @DisplayName("A line under a heading carries the heading and the block's earlier lines; otherwise it carries the line above in its paragraph")
    void carriesLineContext() {
        final AnswerText answer = AnswerText.of("Intro.\nSecond.\n\nAlone.\nMost recent finding:\n- 17/04/2025 one\n- 13/05/2025 two\n**Next**\nthree");

        assertEquals(Arrays.asList(null, "intro.; second.", null, null, "most recent finding:; - 17/04/2025 one",
                "most recent finding:; - 17/04/2025 one; - 13/05/2025 two", null, "next; three"),
                answer.lineContexts().stream().map(c -> c == null ? null : c.text()).toList());
        assertEquals(new AnswerText.LineContext("most recent finding:; - 17/04/2025 one; - 13/05/2025 two", 20, 40, true), answer.lineContexts().get(5));
        assertEquals(new AnswerText.LineContext("intro.; second.", 0, 8, false), answer.lineContexts().get(1));
    }

    @Test
    @DisplayName("The no-data sentinel is recognised, and refusal wording counts in the first two sentences after any heading lines, cited or not")
    void detectsRefusalWording() {
        assertTrue(AnswerText.of("No data available matching the query.").isNoDataSentinel());
        assertFalse(AnswerText.of("The peak was 9.4°C. No data available matching the query.").isNoDataSentinel());
        assertTrue(AnswerText.of("The retrieved documents do not contain information about an insurance policy.").opensWithRefusal());
        assertTrue(AnswerText.of("The policy is not mentioned ::(Source: [r.pdf], Pages 3|3|documentId=" + DOC + ").").opensWithRefusal());
        assertTrue(AnswerText.of("The documents don’t mention a policy.").opensWithRefusal());
        assertTrue(AnswerText.of("A policy number was not found in the documents.").opensWithRefusal());
        assertTrue(AnswerText.of("**Answer**\nThere is no reference to a policy.").opensWithRefusal());
        assertTrue(AnswerText.of("I checked the review. It does not state a policy number.").opensWithRefusal());
        assertFalse(AnswerText.of("The peak was 9.4°C. It was logged at 14:20. The time of the alarm is not provided.").opensWithRefusal());
        assertFalse(AnswerText.of("No breaches have ever been recorded against the supplier.").opensWithRefusal());
        assertFalse(AnswerText.of("").opensWithRefusal());
    }

    @Test
    @DisplayName("Segments split on lines, sentence ends and semicolons, are normalised, and keep decimals and citations out")
    void splitsSegments() {
        final AnswerText answer = AnswerText.of("- 04/07/2017: **penalty** £800.\n- 12/05/2021: peak 9.4°C; later fixed ::(Source: [r.pdf], Pages 3|3|documentId="
                + DOC + "). Done!\n\n");

        assertEquals(List.of("- 04/07/2017: penalty £800.", "- 12/05/2021: peak 9.4°c;", "later fixed .", "done!"), answer.segments());
    }
}
