package uk.gov.moj.cp.ai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatModelUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatModelUtil.class);

    private ChatModelUtil() {
    }

    /**
     * GPT-5 family and o-series (o1, o3, o4-mini, etc.) are "reasoning models" that reject the
     * legacy {@code max_tokens} parameter and any non-default sampling parameters
     * (temperature/top_p must be 1.0 or omitted). Detect by deployment name prefix.
     */
    public static boolean isReasoningModel(final String deploymentName) {
        if (deploymentName == null) {
            return false;
        }
        final String d = deploymentName.toLowerCase();
        return d.startsWith("gpt-5") || d.startsWith("gpt5")
                || d.startsWith("o1") || d.startsWith("o3") || d.startsWith("o4");
    }

    /**
     * Extracts the raw JSON object from an LLM response by trimming anything outside the outermost
     * braces (e.g. Markdown code fences). Returns the original string unchanged if no {@code {...}}
     * pair is found.
     */
    public static String ensureRawJsonAsConvertingPayloadToObject(final String llmResponse) {
        if (llmResponse.contains("```")) {
            LOGGER.info("LLM response contains \"```\" and will require sanitising");
        }

        final int firstBrace = llmResponse.indexOf("{");
        final int lastBrace = llmResponse.lastIndexOf("}");

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return llmResponse.substring(firstBrace, lastBrace + 1);
        }
        return llmResponse; // Fallback to original if no braces found
    }
}
