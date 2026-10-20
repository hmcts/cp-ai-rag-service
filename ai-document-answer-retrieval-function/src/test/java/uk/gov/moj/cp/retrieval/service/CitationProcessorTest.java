package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.doc;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.scopeOf;

import uk.gov.moj.cp.retrieval.model.CitationScope;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CitationProcessorTest {

    private static final CitationScope SCOPE = scopeOf(doc("doc123", "file.pdf", 10, 11, 12, 13));

    private CitationProcessor citationProcessor;

    @BeforeEach
    void setUp() {
        citationProcessor = new CitationProcessor();
    }

    @Test
    void processAndFormatCitations_ReturnsFormattedText_WhenValidInputProvided() throws Exception {
        String rawLlmOutput = "Answer text with citation [1] and another citation [2] <FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"file.pdf\",\"pageNumbers\":\"10-12\",\"individualPageNumbers\":\"10,11,12\",\"documentId\":\"doc123\"},{\"citationId\":\"2\",\"documentFilename\":\"file.pdf\",\"pageNumbers\":\"13\",\"individualPageNumbers\":\"13\",\"documentId\":\"doc123\"}]</FACT_MAP_JSON>";
        String expectedOutput = "Answer text with citation ::(Source: [file.pdf], Pages 10-12|10,11,12|documentId=doc123) and another citation ::(Source: [file.pdf], Pages 13|13|documentId=doc123)";

        String result = citationProcessor.processAndFormatCitations(rawLlmOutput, SCOPE);

        assertEquals(expectedOutput, result);
    }

    @Test
    void processAndFormatCitations_ReturnsRawText_WhenJsonTagMissing() {
        String rawLlmOutput = "Answer text without JSON tags";
        String result = citationProcessor.processAndFormatCitations(rawLlmOutput, SCOPE);
        assertEquals("Answer text without JSON tags", result);
    }

    @Test
    void processAndFormatCitations_ReturnsRawText_WhenJsonParsingFails() throws Exception {
        String rawLlmOutput = "Answer text <FACT_MAP_JSON>invalid_json</FACT_MAP_JSON>";

        String result = citationProcessor.processAndFormatCitations(rawLlmOutput, SCOPE);

        assertEquals("Answer text", result);
    }

    @Test
    void processAndFormatCitations_ReturnsTrimmedText_WhenInputIsEmpty() {
        String result = citationProcessor.processAndFormatCitations("", SCOPE);
        assertEquals("", result);
    }

    @Test
    void processAndFormatCitations_ReturnsTrimmedText_WhenInputIsNull() {
        String result = citationProcessor.processAndFormatCitations(null, SCOPE);
        assertEquals("", result);
    }

    @Test
    void processAndFormatCitations_RendersNoCitation_WhenScopeIsNullOrEmpty() {
        String rawLlmOutput = "Answer text with citation [1] <FACT_MAP_JSON>[{\"citationId\":\"1\",\"documentFilename\":\"file.pdf\",\"individualPageNumbers\":\"10\",\"documentId\":\"doc123\"}]</FACT_MAP_JSON>";

        assertEquals("Answer text with citation", citationProcessor.processAndFormatCitations(rawLlmOutput, null));
        assertEquals("Answer text with citation",
                citationProcessor.processAndFormatCitations(rawLlmOutput, CitationScope.empty()));
    }
}
