package uk.gov.moj.cp.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus;
import uk.gov.moj.cp.harness.ResponseQualityComparator.JudgeResult;
import uk.gov.moj.cp.harness.ResponseQualityComparator.StructureCounts;
import uk.gov.moj.cp.harness.TestHarness.RunResult;
import uk.gov.moj.cp.harness.TestHarness.UserQueryConfig;
import uk.gov.moj.cp.retrieval.model.LlmResponse;

/**
 * Unit tests for the pure, deterministic logic of {@link ResponseQualityComparator}. The I/O-bound
 * paths (embedding, LLM judge calls) are deliberately not exercised here — these tests protect the
 * row-pairing, corner-selection, metric and parsing logic whose correctness the evaluation results
 * depend on.
 */
class ResponseQualityComparatorTest {

    private static final List<String> VERSIONS = List.of("prod", "test");
    private static final List<String> MODELS = List.of("gpt-4o-response-generation", "gpt-5.1");

    private static RunResult result(final String queryLabel) {
        return new RunResult("v4", "gpt-4o", queryLabel, 1, null, 0L, null);
    }

    private static UserQueryConfig query(final String label, final String version) {
        return new UserQueryConfig(label, "userQuery", "queryPrompt", "docId", version);
    }

    // ---- baseLabel: strips the #version suffix, keeps the document suffix (pairing invariant) ----

    @Test
    void baseLabel_stripsVersionSuffix_keepsDocumentSuffix() {
        final RunResult r = result("Summary of the facts @3f326c23 #test");
        final Map<String, UserQueryConfig> byLabel = new HashMap<>();
        byLabel.put(r.queryLabel(), query(r.queryLabel(), "test"));

        assertEquals("Summary of the facts @3f326c23", ResponseQualityComparator.baseLabel(r, byLabel));
    }

    @Test
    void baseLabel_returnsQueryLabelUnchanged_whenConfigMissing() {
        final RunResult r = result("Some query @3f326c23 #test");
        assertEquals(r.queryLabel(), ResponseQualityComparator.baseLabel(r, new HashMap<>()));
    }

    @Test
    void baseLabel_returnsQueryLabelUnchanged_whenVersionNull() {
        final RunResult r = result("Some query @3f326c23");
        final Map<String, UserQueryConfig> byLabel = new HashMap<>();
        byLabel.put(r.queryLabel(), query(r.queryLabel(), null));
        assertEquals(r.queryLabel(), ResponseQualityComparator.baseLabel(r, byLabel));
    }

    // ---- versionOf ----

    @Test
    void versionOf_returnsConfiguredVersion() {
        final RunResult r = result("Q @doc #prod");
        final Map<String, UserQueryConfig> byLabel = new HashMap<>();
        byLabel.put(r.queryLabel(), query(r.queryLabel(), "prod"));
        assertEquals("prod", ResponseQualityComparator.versionOf(r, byLabel));
    }

    @Test
    void versionOf_returnsQuestionMark_whenUnknownOrNull() {
        final RunResult missing = result("Q @doc #prod");
        assertEquals("?", ResponseQualityComparator.versionOf(missing, new HashMap<>()));

        final Map<String, UserQueryConfig> byLabel = new HashMap<>();
        byLabel.put(missing.queryLabel(), query(missing.queryLabel(), null));
        assertEquals("?", ResponseQualityComparator.versionOf(missing, byLabel));
    }

    // ---- cornerKey ----

    @Test
    void cornerKey_convertsFirstColonToPipeAndTrims() {
        assertEquals("prod|gpt-4o-response-generation", ResponseQualityComparator.cornerKey("prod:gpt-4o-response-generation"));
        assertEquals("prod|gpt-4o", ResponseQualityComparator.cornerKey("  prod : gpt-4o  "));
    }

    @Test
    void cornerKey_returnsTrimmedInput_whenNoColon() {
        assertEquals("prod", ResponseQualityComparator.cornerKey("  prod  "));
    }

    // ---- crossCutCorners: default diagonal + HARNESS_CROSSCUT override + malformed fallback ----

    @Test
    void crossCutCorners_defaultDiagonal_isFirstVersionModelVsLastVersionModel() {
        assertArrayEquals(
                new String[] {"prod|gpt-4o-response-generation", "test|gpt-5.1"},
                ResponseQualityComparator.crossCutCorners(VERSIONS, MODELS, ""));
    }

    @Test
    void crossCutCorners_honoursExplicitOverride() {
        assertArrayEquals(
                new String[] {"prod|gpt-4o-response-generation", "test|gpt-5.1"},
                ResponseQualityComparator.crossCutCorners(VERSIONS, MODELS,
                        "prod:gpt-4o-response-generation vs test:gpt-5.1"));
    }

    @Test
    void crossCutCorners_overrideToleratesExtraWhitespace() {
        assertArrayEquals(
                new String[] {"test|gpt-5.1", "prod|gpt-4o"},
                ResponseQualityComparator.crossCutCorners(VERSIONS, MODELS,
                        "test:gpt-5.1   vs   prod:gpt-4o"));
    }

    @Test
    void crossCutCorners_fallsBackToDefault_whenSpecMalformed() {
        assertArrayEquals(
                new String[] {"prod|gpt-4o-response-generation", "test|gpt-5.1"},
                ResponseQualityComparator.crossCutCorners(VERSIONS, MODELS, "not-a-valid-pair"));
    }

    // ---- groupRows ----

    @Test
    void groupRows_groupsByRowKey_thenVariantKey() {
        final RunResult a = result("Q @doc #prod");
        final RunResult b = result("Q @doc #test");
        final Map<String, Map<String, RunResult>> rows = ResponseQualityComparator.groupRows(
                List.of(a, b),
                r -> "Q@doc",                                  // same row for both
                r -> r.queryLabel().endsWith("#prod") ? "prod" : "test");

        assertEquals(1, rows.size());
        assertEquals(2, rows.get("Q@doc").size());
        assertSame(a, rows.get("Q@doc").get("prod"));
        assertSame(b, rows.get("Q@doc").get("test"));
    }

    // ---- okResult: only ANSWER_GENERATED with no error passes ----

    @Test
    void okResult_returnsResult_whenAnswerGeneratedAndNoError() {
        final RunResult r = new RunResult("v4", "gpt-4o", "Q", 1,
                new LlmResponse("raw", "formatted", AnswerGenerationStatus.ANSWER_GENERATED), 10L, null);
        assertSame(r, ResponseQualityComparator.okResult(r));
    }

    @Test
    void okResult_returnsNull_forNullErroredMissingResponseOrNonTerminalStatus() {
        assertNull(ResponseQualityComparator.okResult(null));
        assertNull(ResponseQualityComparator.okResult(
                new RunResult("v4", "gpt-4o", "Q", 1, null, 0L, "boom")));
        assertNull(ResponseQualityComparator.okResult(
                new RunResult("v4", "gpt-4o", "Q", 1, null, 0L, null)));
        assertNull(ResponseQualityComparator.okResult(new RunResult("v4", "gpt-4o", "Q", 1,
                new LlmResponse("raw", "formatted", AnswerGenerationStatus.ANSWER_GENERATION_FAILED), 0L, null)));
    }

    // ---- structureOf ----

    @Test
    void structureOf_countsH1HeadingsAndBullets() {
        final String md = "# Title\n## Section\n### Sub\n- a\n* b\nplain text\n#nospace";
        final StructureCounts c = ResponseQualityComparator.structureOf(md);
        assertEquals(1, c.h1(), "one '# ' heading");
        assertEquals(2, c.headings(), "'## ' and '### ' both count as h2+ headings");
        assertEquals(2, c.bullets(), "'- ' and '* ' bullets");
    }

    @Test
    void structureOf_handlesNull() {
        final StructureCounts c = ResponseQualityComparator.structureOf(null);
        assertEquals(0, c.h1());
        assertEquals(0, c.headings());
        assertEquals(0, c.bullets());
    }

    // ---- parseJudgeJson: robust to surrounding text, partial/absent fields, and junk ----

    @Test
    void parseJudgeJson_parsesWellFormedVerdict() {
        final JudgeResult j = ResponseQualityComparator.parseJudgeJson(
                "{\"semanticVerdict\":\"B_RICHER\",\"factsMissingFromA\":[\"x\",\"y\"],"
                        + "\"factsMissingFromB\":[],\"structureScoreA\":4,\"structureScoreB\":5,\"note\":\"denser\"}");
        assertEquals("B_RICHER", j.verdict());
        assertEquals(2, j.missingFromA());
        assertEquals(0, j.missingFromB());
        assertEquals(4, j.structureA());
        assertEquals(5, j.structureB());
        assertEquals("denser", j.note());
    }

    @Test
    void parseJudgeJson_extractsJsonEmbeddedInSurroundingText() {
        final JudgeResult j = ResponseQualityComparator.parseJudgeJson(
                "Sure — here you go: {\"semanticVerdict\":\"EQUIVALENT\"} (hope that helps)");
        assertEquals("EQUIVALENT", j.verdict());
    }

    @Test
    void parseJudgeJson_defaultsMissingFields() {
        final JudgeResult j = ResponseQualityComparator.parseJudgeJson("{}");
        assertEquals("UNPARSED", j.verdict());
        assertEquals(0, j.missingFromA());
        assertEquals(0, j.structureA());
        assertEquals("", j.note());
    }

    @Test
    void parseJudgeJson_returnsNull_whenNoJsonObjectOrUnparseable() {
        assertNull(ResponseQualityComparator.parseJudgeJson("no json here"));
        assertNull(ResponseQualityComparator.parseJudgeJson("{ not valid json "));
    }

    @Test
    void parseJudgeJson_truncatesLongNoteTo100Chars() {
        final String longNote = "x".repeat(150);
        final JudgeResult j = ResponseQualityComparator.parseJudgeJson(
                "{\"semanticVerdict\":\"EQUIVALENT\",\"note\":\"" + longNote + "\"}");
        assertEquals(100, j.note().length());
        assertTrue(j.note().endsWith("…"));
    }
}
