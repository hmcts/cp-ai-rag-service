package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.CITATION_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_FILE_NAME;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.DOCUMENT_ID;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.INDIVIDUAL_PAGE_NUMBERS;
import static uk.gov.moj.cp.retrieval.model.CitationKeys.PAGE_NUMBERS;

import uk.gov.moj.cp.ai.util.StringUtil;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CitationProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CitationProcessor.class);

    // Regex pattern to safely extract the JSON payload contained between the unique tags.
    // Pattern.DOTALL allows the dot (.) to match newlines within the JSON content.
    private static final Pattern JSON_TAG_PATTERN =
            Pattern.compile("<FACT_MAP_JSON>(.*?)</FACT_MAP_JSON>", Pattern.DOTALL);

    private final ObjectMapper objectMapper = getObjectMapper();

    /**
     * Executes the post-processing pipeline: extracts structured citations and substitutes
     * numerical placeholders in the answer text with the fully compliant citation string.
     *
     * @param rawLlmOutput The raw text output containing the narrative and the embedded JSON.
     * @return The clean, DAC/NFT compliant, and fully cited response text.
     */
    public String processAndFormatCitations(String rawLlmOutput) {
        if (StringUtil.isNullOrEmpty(rawLlmOutput)) {
            return "";
        }

        // 1. Extract the JSON payload and the raw narrative text.
        final Matcher matcher = JSON_TAG_PATTERN.matcher(rawLlmOutput);

        if (!matcher.find()) {
            // If the JSON tag is missing, return the raw output and warn.
            LOGGER.warn("Raw data output missing required <FACT_MAP_JSON> tags. Cannot verify or format citations.");
            return rawLlmOutput.trim();
        }

        // Text is everything before the JSON tag (the narrative answer)
        String answerText = rawLlmOutput.substring(0, matcher.start()).trim();
        // JSON is the content captured within the tags (Group 1)
        final String jsonPayload = matcher.group(1).trim();

        try {
            // 2. Parse the JSON payload into a list of structured citation maps
            List<Map<String, String>> citations = objectMapper.readValue(jsonPayload, new TypeReference<>() {});

            // 3. Iterate through structured citations and perform substitutions
            for (Map<String, String> citation : citations) {

                String citationIdStr = citation.get(CITATION_ID);

                // Construct the GUARANTEED EXACT SYNTAX required for compliance
                String formattedCitation = String.format(
                        "::(Source: [%s], Pages %s|%s|documentId=%s)",
                        citation.getOrDefault(DOCUMENT_FILE_NAME, "UNKNOWN_FILE"),
                        citation.getOrDefault(PAGE_NUMBERS, "N/A"), // The page range (e.g., 10-12,14,20)
                        citation.getOrDefault(INDIVIDUAL_PAGE_NUMBERS, "N/A"), // Repeated for the individual page list placeholder
                        citation.getOrDefault(DOCUMENT_ID, "UNKNOWN_ID")
                );

                // Find the placeholder (e.g., "[1]") and replace it in the answer text.
                // We escape the brackets because they are special characters in Regex.
                String placeholderRegex = "\\[" + citationIdStr + "\\]";
                answerText = answerText.replaceAll(placeholderRegex, formattedCitation);
            }

            // 4. Perform final cleanup of any residual whitespace or trailing markers
            return answerText.trim();

        } catch (Exception e) {
            LOGGER.warn("Failed to parse citation JSON or substitute placeholders. Returning raw text.", e);
            // In case of parsing failure, return the narrative and append a clear error message.
            return answerText;
        }
    }
}