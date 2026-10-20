package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_A_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_FILE;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.DOC_B_ID;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.chunksOf;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docA;
import static uk.gov.moj.cp.retrieval.service.CitationScopeFixtures.docB;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
import uk.gov.moj.cp.retrieval.model.CitationScope;
import uk.gov.moj.cp.retrieval.model.LlmResponse;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Citation validation through the citation guard, with the real {@link CitationProcessor} and
 * {@link ChunkFormatterUtility} and only the model and the instruction builder mocked.
 * Fixtures: document A ({@code report-a.pdf}, pages {2, 3}) and document B ({@code notes-b.pdf},
 * page {5}).
 */
class ResponseGenerationServiceCitationValidationTest {

    private static final String SYSTEM_PROMPT = "system prompt";
    private static final String USER_QUERY = "Which items are listed?";
    private static final String QUERY_PROMPT = "Answer briefly.";
    private static final String INSTRUCTION = "user instruction";
    private static final String GARBLED_A_ID = "aaaaaaaa-0000-4000-8000-0000000000";

    private final List<ChunkedEntry> chunks = chunksOf(docA(), docB());

    private ChatService chatService;
    private UserInstructionService userInstructionService;
    private CitationProcessor citationProcessor;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        userInstructionService = mock(UserInstructionService.class);
        citationProcessor = spy(new CitationProcessor());
        when(userInstructionService.buildUserInstruction(eq(USER_QUERY), eq(QUERY_PROMPT), anyString()))
                .thenReturn(INSTRUCTION);
    }

    private ResponseGenerationService service(final CitationGuardMode mode) {
        return new ResponseGenerationService(chatService, citationProcessor, new ChunkFormatterUtility(),
                userInstructionService, SYSTEM_PROMPT, mode);
    }

    private void modelReturns(final String raw) throws ChatServiceException {
        when(chatService.callModel(SYSTEM_PROMPT, INSTRUCTION, String.class)).thenReturn(Optional.of(raw));
    }

    private static String entry(final int citationId, final String documentId, final String fileName,
                                final String pages) {
        return "{\"citationId\":" + citationId + ",\"documentId\":\"" + documentId + "\",\"documentFilename\":\""
                + fileName + "\",\"individualPageNumbers\":\"" + pages + "\"}";
    }

    private static String answer(final String text, final String... entries) {
        return text + " <FACT_MAP_JSON>[" + String.join(",", entries) + "]</FACT_MAP_JSON>";
    }

    private static final String ALL_INVALID = answer("The first item is listed [1]. The second item is listed [2].",
            entry(1, GARBLED_A_ID, DOC_A_FILE, "2"), entry(2, DOC_B_ID, DOC_A_FILE, "5"));

    @Test
    void processorReceivesTheScopeOfTheChunksSentToTheModel() throws ChatServiceException {
        final String raw = answer("The first item is listed [1].", entry(1, DOC_A_ID, DOC_A_FILE, "2"));
        modelReturns(raw);

        service(CitationGuardMode.DELIVER).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

        verify(citationProcessor).processCitations(eq(raw), eq(CitationScope.from(chunks)));
    }

    @Test
    void everyEntryInvalid_deliver_throwsWithInvalidCount() throws ChatServiceException {
        modelReturns(ALL_INVALID);

        final CitationDegradedException ex = assertThrows(CitationDegradedException.class,
                () -> service(CitationGuardMode.DELIVER).generateResponse(USER_QUERY, chunks, QUERY_PROMPT));

        assertEquals("[guard=deliver] Citations missing: jsonBlock=true, inlineMarkers=2, rendered=0, stripped=2, "
                + "invalid=2", ex.getMessage());
        assertFalse(ex.formattedText().contains("::(Source:"), ex.formattedText());
    }

    @Test
    void everyEntryInvalid_reject_throwsSoTheCallerAppliesTheRejectPolicy() throws ChatServiceException {
        modelReturns(ALL_INVALID);

        final CitationDegradedException ex = assertThrows(CitationDegradedException.class,
                () -> service(CitationGuardMode.REJECT).generateResponse(USER_QUERY, chunks, QUERY_PROMPT));

        assertTrue(ex.getMessage().startsWith("[guard=reject] Citations missing:"), ex.getMessage());
        assertTrue(ex.getMessage().endsWith(", invalid=2"), ex.getMessage());
    }

    @Test
    void everyEntryInvalid_off_deliversWithReason() throws ChatServiceException {
        modelReturns(ALL_INVALID);

        final LlmResponse result = service(CitationGuardMode.OFF).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

        assertEquals(ANSWER_GENERATED, result.status());
        assertEquals("[guard=off] Citations missing: jsonBlock=true, inlineMarkers=2, rendered=0, stripped=2, "
                + "invalid=2", result.reason());
    }

    @Test
    void genuineEmptyFactMap_isAnAcceptedRefusalWithoutReason() throws ChatServiceException {
        modelReturns(answer("The retrieved documents do not list any items."));

        final LlmResponse result = service(CitationGuardMode.OFF).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

        assertEquals(ANSWER_GENERATED, result.status());
        assertNull(result.reason());
    }

    @Test
    void degradedWithNothingDropped_reportsInvalidZero() throws ChatServiceException {
        modelReturns("The first item is listed [1].");

        final LlmResponse result = service(CitationGuardMode.OFF).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

        assertTrue(result.reason().endsWith(", invalid=0"), result.reason());
    }

    @Test
    void partialInvalidity_isAcceptedWithoutReasonAndLogsExactlyOneCountsOnlyWarn() throws ChatServiceException {
        final String text = "The first item is listed [1]. The second item is listed [2]. The third item is listed [3].";
        modelReturns(answer(text,
                entry(1, DOC_A_ID, DOC_A_FILE, "2"),
                entry(2, GARBLED_A_ID, DOC_A_FILE, "3"),
                entry(3, DOC_B_ID, DOC_B_FILE, "5")));

        try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
            final LlmResponse result = service(CitationGuardMode.DELIVER)
                    .generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

            assertEquals(ANSWER_GENERATED, result.status());
            assertNull(result.reason());
            final List<String> warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), "exactly one WARN across both loggers: " + warnings);
            final String warn = warnings.get(0);
            assertTrue(warn.contains("entries=3"), warn);
            assertTrue(warn.contains("invalid=1"), warn);
            assertTrue(warn.contains("causeDocument=1"), warn);
            assertTrue(warn.contains("trimmedPages=0"), warn);
            assertTrue(warn.contains("rendered=2"), warn);
            assertCountsOnly(warn, text);
        }
    }

    @Test
    void onlyPagesTrimmed_isAcceptedWithoutReasonAndWarnsWithTrimmedPages() throws ChatServiceException {
        modelReturns(answer("The first item is listed [1].", entry(1, DOC_A_ID, DOC_A_FILE, "2, 9")));

        try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
            final LlmResponse result = service(CitationGuardMode.DELIVER)
                    .generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

            assertNull(result.reason());
            final List<String> warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("invalid=0"), warnings.get(0));
            assertTrue(warnings.get(0).contains("trimmedPages=1"), warnings.get(0));
        }
    }

    @Test
    void cleanAnswer_logsNoWarnAndAnInfoWithZeroCounts() throws ChatServiceException {
        modelReturns(answer("The first item is listed [1].", entry(1, DOC_A_ID, DOC_A_FILE, "2")));

        try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
            final LlmResponse result = service(CitationGuardMode.DELIVER)
                    .generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

            assertNull(result.reason());
            assertTrue(logs.messages(Level.WARN).isEmpty(), logs.messages(Level.WARN).toString());
            assertTrue(logs.messages(Level.INFO).stream()
                            .anyMatch(m -> m.startsWith("Citation guard [deliver]: accepted cited answer")
                                    && m.contains("invalid=0, trimmedPages=0")),
                    logs.messages(Level.INFO).toString());
        }
    }

    @Test
    void degradedAnswer_logsOnlyTheGuardWarn() throws ChatServiceException {
        modelReturns(ALL_INVALID);

        try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
            service(CitationGuardMode.OFF).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

            final List<String> warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("invalid=2"), warnings.get(0));
        }
    }

    @Test
    void mergeLine_carriesNoDocumentIdOrFilename() throws ChatServiceException {
        modelReturns(answer("Both items are listed [1][2].",
                entry(1, DOC_A_ID, DOC_A_FILE, "2"), entry(2, DOC_A_ID, DOC_A_FILE, "3")));

        try (LogCapture logs = LogCapture.of(ResponseGenerationService.class, CitationProcessor.class)) {
            service(CitationGuardMode.DELIVER).generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

            final List<String> merges = logs.messages(Level.INFO).stream()
                    .filter(m -> m.startsWith("Merged "))
                    .toList();
            assertEquals(1, merges.size(), logs.messages().toString());
            assertFalse(merges.get(0).contains(DOC_A_ID), merges.get(0));
            assertFalse(merges.get(0).contains(DOC_A_FILE), merges.get(0));
            assertFalse(merges.get(0).contains("documentId="), merges.get(0));
        }
    }

    @Test
    void modelIsCalledOnceAndIdenticalInputsGiveIdenticalOutput() throws ChatServiceException {
        final String raw = answer("The first item is listed [1].",
                entry(1, DOC_A_ID, DOC_A_FILE, "3, 2"), entry(2, GARBLED_A_ID, DOC_A_FILE, "2"));
        modelReturns(raw);
        final ResponseGenerationService service = service(CitationGuardMode.DELIVER);

        final LlmResponse first = service.generateResponse(USER_QUERY, chunks, QUERY_PROMPT);
        verify(chatService, times(1)).callModel(SYSTEM_PROMPT, INSTRUCTION, String.class);
        final LlmResponse second = service.generateResponse(USER_QUERY, chunks, QUERY_PROMPT);

        assertEquals(first, second);
        verify(chatService, times(2)).callModel(SYSTEM_PROMPT, INSTRUCTION, String.class);
    }

    @Test
    void eachAttemptValidatesAgainstItsOwnChunks() throws ChatServiceException {
        final String raw = answer("The first item is listed [1].", entry(1, DOC_A_ID, DOC_A_FILE, "2"));
        modelReturns(raw);
        final ResponseGenerationService service = service(CitationGuardMode.OFF);

        final LlmResponse withA = service.generateResponse(USER_QUERY, chunksOf(docA()), QUERY_PROMPT);
        final LlmResponse withoutA = service.generateResponse(USER_QUERY, chunksOf(docB()), QUERY_PROMPT);

        assertNull(withA.reason());
        assertTrue(withA.formattedLlmResponse().contains("documentId=" + DOC_A_ID), withA.formattedLlmResponse());
        assertTrue(withoutA.reason().endsWith(", invalid=1"), withoutA.reason());
        assertFalse(withoutA.formattedLlmResponse().contains("::(Source:"), withoutA.formattedLlmResponse());
    }

    /** NFR-4 / AC-27: counts only — no ids, filenames, query or answer text, no literal documentId= token. */
    private static void assertCountsOnly(final String message, final String answerText) {
        for (final String forbidden : List.of(DOC_A_ID, DOC_B_ID, GARBLED_A_ID, DOC_A_FILE, DOC_B_FILE,
                USER_QUERY, QUERY_PROMPT, answerText, "The first item", "documentId=")) {
            assertFalse(message.contains(forbidden), "WARN must not contain '" + forbidden + "': " + message);
        }
    }
}
