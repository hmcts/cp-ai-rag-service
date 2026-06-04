package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatModelUtilTest {

    @Test
    @DisplayName("Recognises GPT-5 and o-series prefixes as reasoning models")
    void recognisesReasoningModels() {
        assertTrue(ChatModelUtil.isReasoningModel("gpt-5"));
        assertTrue(ChatModelUtil.isReasoningModel("gpt-5.1"));
        assertTrue(ChatModelUtil.isReasoningModel("gpt5-deployment"));
        assertTrue(ChatModelUtil.isReasoningModel("o1-preview"));
        assertTrue(ChatModelUtil.isReasoningModel("o3"));
        assertTrue(ChatModelUtil.isReasoningModel("o4-mini"));
    }

    @Test
    @DisplayName("Prefix detection is case-insensitive")
    void detectionIsCaseInsensitive() {
        assertTrue(ChatModelUtil.isReasoningModel("GPT-5-MINI"));
        assertTrue(ChatModelUtil.isReasoningModel("O3"));
    }

    @Test
    @DisplayName("Treats GPT-4 family and arbitrary names as non-reasoning models")
    void treatsOtherModelsAsNonReasoning() {
        assertFalse(ChatModelUtil.isReasoningModel("gpt-4o"));
        assertFalse(ChatModelUtil.isReasoningModel("gpt-4-turbo"));
        assertFalse(ChatModelUtil.isReasoningModel("deploymentName"));
    }

    @Test
    @DisplayName("Null deployment name is not a reasoning model")
    void nullDeploymentIsNotReasoning() {
        assertFalse(ChatModelUtil.isReasoningModel(null));
    }

    @Test
    @DisplayName("Extracts the JSON object from a fenced response")
    void extractsJsonFromFencedResponse() {
        assertEquals("{\"key\":\"value\"}",
                ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject("```json{\"key\":\"value\"}```"));
    }

    @Test
    @DisplayName("Returns the JSON object unchanged when there is no surrounding noise")
    void returnsCleanJsonUnchanged() {
        assertEquals("{\"key\":\"value\"}",
                ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject("{\"key\":\"value\"}"));
    }

    @Test
    @DisplayName("Trims leading and trailing prose around the JSON object")
    void trimsSurroundingProse() {
        assertEquals("{\"a\":1}",
                ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject("Here is the result: {\"a\":1} thanks"));
    }

    @Test
    @DisplayName("Returns the original string when no braces are present")
    void returnsOriginalWhenNoBraces() {
        assertEquals("invalid_json",
                ChatModelUtil.ensureRawJsonAsConvertingPayloadToObject("invalid_json"));
    }
}
