package uk.gov.moj.cp.retrieval.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.retrieval.service.ResponseGenerationService.LLM_RESPONSE_FAILURE_TO_GENERATE;
import static uk.gov.moj.cp.retrieval.service.ResponseGenerationService.LLM_RESPONSE_NO_DATA_AVAILABLE;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.CitationProcessor.CitationOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ResponseGenerationServiceTest {

    @Mock
    private ChatService mockChatService;

    @Mock
    private CitationProcessor citationProcessor;

    @Mock
    private ChunkFormatterUtility chunkFormatterUtility;

    @Mock
    private UserInstructionService userInstructionService;

    private ResponseGenerationService responseGenerationService;

    final String mockFormattedLlmResponse = "Some random processed response";
    final String mockFormattedChunk = "Some random formatted chunk entry";
    final String mockUserInstructions = "Some random generated user instructions";
    final String mockSystemPromptTemplate = "Some random prompt template";

    private static CitationOutcome citedOutcome(final String formatted) {
        return new CitationOutcome(formatted, true, false, 1, 1, 0);
    }

    private static CitationOutcome degradedOutcome(final String formatted) {
        return new CitationOutcome(formatted, false, false, 3, 0, 3);
    }

    private ResponseGenerationService serviceWithGuard(final CitationGuardMode mode) {
        return new ResponseGenerationService(mockChatService, citationProcessor, chunkFormatterUtility,
                userInstructionService, mockSystemPromptTemplate, mode);
    }

    @BeforeEach
    void setUp() {
        openMocks(this);
        // Explicit guard mode so tests do not depend on environment variables.
        responseGenerationService = serviceWithGuard(CitationGuardMode.DELIVER);
    }

    @Test
    void generateResponse_ReturnsTrimmedAndCitationFormattedResponse_WhenChatServiceReturnsValidResponse() throws ChatServiceException {
        final String userQuery = "What is the legal implication?";
        final String userQueryPrompt = "Provide detailed legal advice.";
        final String mockRawLlmResponse = "Valid AI Response [1]] " +
                "and even more response with faulty citation [2]";

        final List<ChunkedEntry> chunkedEntries = new ArrayList<>();
        chunkedEntries.add(ChunkedEntry.builder()
                .id("id1")
                .chunk("Chunk 1")
                .documentFileName("file name 1")
                .pageNumber(1)
                .documentId("2876")
                .build());


        when(citationProcessor.processCitations(mockRawLlmResponse)).thenReturn(citedOutcome(mockFormattedLlmResponse));
        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.of(mockRawLlmResponse));

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(mockFormattedLlmResponse, result.formattedLlmResponse());
        assertEquals(mockRawLlmResponse, result.rawLlmResponse());
        assertEquals(ANSWER_GENERATED, result.status());
        verify(citationProcessor).processCitations(mockRawLlmResponse);
        verify(chunkFormatterUtility).buildChunkContext(chunkedEntries);
        verify(userInstructionService).buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk);
        verify(mockChatService).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
    }

    @Test
    void generateResponse_ReturnsNoResponseMessage_WhenChatServiceReturnsEmpty() throws ChatServiceException {
        final String userQuery = "What is the legal implication?";
        final String userQueryPrompt = "Provide detailed legal advice.";
        final List<ChunkedEntry> chunkedEntries = List.of(
                ChunkedEntry.builder()
                        .id("id1")
                        .chunk("Chunk 1")
                        .documentFileName("file name 1")
                        .pageNumber(1)
                        .documentId("doc id")
                        .build()
        );

        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.empty());

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(LLM_RESPONSE_FAILURE_TO_GENERATE, result.rawLlmResponse());
        assertEquals(LLM_RESPONSE_FAILURE_TO_GENERATE, result.formattedLlmResponse());
        assertEquals(ANSWER_GENERATION_FAILED, result.status());

        // Single attempt: retries are the callers' concern (queue redelivery on the async path).
        verify(mockChatService, times(1)).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
        verify(citationProcessor, never()).processCitations(any());
        verify(chunkFormatterUtility).buildChunkContext(chunkedEntries);
        verify(userInstructionService).buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk);
    }

    @Test
    void generateResponse_propagatesException_WhenChatServiceThrowsChatServiceException() throws ChatServiceException {
        final String userQuery = "What is the legal implication?";
        final String userQueryPrompt = "Provide detailed legal advice.";
        final List<ChunkedEntry> chunkedEntries = List.of(
                ChunkedEntry.builder()
                        .id("id1")
                        .chunk("Chunk 1")
                        .documentFileName("file name 1")
                        .pageNumber(1)
                        .documentId("doc id")
                        .build()
        );

        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenThrow(new ChatServiceException("Invalid json response error"));

        final ChatServiceException ex = assertThrows(
                ChatServiceException.class,
                () -> responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt)
        );

        assertThat(ex.getMessage(), is("Invalid json response error"));
    }

    @Test
    void generateResponse_ThrowsException_WhenChatServiceThrowsChatServiceException() throws ChatServiceException {
        final String userQuery = "What is the legal implication?";
        final String userQueryPrompt = "Provide detailed legal advice.";
        final List<ChunkedEntry> chunkedEntries = List.of(
                ChunkedEntry.builder()
                        .id("id1")
                        .chunk("Chunk 1")
                        .documentFileName("file name 1")
                        .pageNumber(1)
                        .documentId("doc id")
                        .build()
        );

        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenThrow(new HttpResponseException("Service error", mock(HttpResponse.class)));

        final HttpResponseException ex = assertThrows(
                HttpResponseException.class,
                () -> responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt)
        );

        assertThat(ex.getMessage(), is("Service error"));
    }

    @Test
    void generateResponse_ReturnsDefaultContextMessage_WhenChunkedEntriesAreNullOrEmpty() throws ChatServiceException {
        final String userQuery = "What is the legal implication?";
        final String userQueryPrompt = "Provide detailed legal advice.";

        LlmResponse result = responseGenerationService.generateResponse(userQuery, null, userQueryPrompt);
        assertEquals(LLM_RESPONSE_NO_DATA_AVAILABLE, result.rawLlmResponse());
        assertEquals(LLM_RESPONSE_NO_DATA_AVAILABLE, result.formattedLlmResponse());
        assertEquals(ANSWER_GENERATED, result.status());

        result = responseGenerationService.generateResponse(userQuery, List.of(), userQueryPrompt);
        assertEquals(LLM_RESPONSE_NO_DATA_AVAILABLE, result.rawLlmResponse());
        assertEquals(LLM_RESPONSE_NO_DATA_AVAILABLE, result.formattedLlmResponse());
        assertEquals(ANSWER_GENERATED, result.status());

        verify(mockChatService, never()).callModel(anyString(), eq(userQuery), eq(String.class));
    }

    // ---- citation guard ------------------------------------------------------

    private List<ChunkedEntry> stubbedChunks(final String userQuery, final String userQueryPrompt) {
        final List<ChunkedEntry> chunkedEntries = List.of(ChunkedEntry.builder()
                .id("id1").chunk("Chunk 1").documentFileName("file name 1").pageNumber(1).documentId("docA")
                .build());
        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        return chunkedEntries;
    }

    @Test
    void citationGuard_ThrowsCitationDegradedException_CarryingTheDegradedAnswer() throws ChatServiceException {
        final String userQuery = "query";
        final String userQueryPrompt = "prompt";
        final List<ChunkedEntry> chunkedEntries = stubbedChunks(userQuery, userQueryPrompt);

        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.of("uncited raw"));
        when(citationProcessor.processCitations("uncited raw")).thenReturn(degradedOutcome("uncited formatted"));

        final CitationDegradedException ex = assertThrows(CitationDegradedException.class,
                () -> responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt));

        assertEquals("uncited raw", ex.rawLlmResponse());
        assertEquals("uncited formatted", ex.formattedText());
        assertThat(ex.getMessage(), is("Citations missing: jsonBlock=false, inlineMarkers=3, rendered=0, stripped=3"));
        // Single attempt only — retry policy belongs to the caller (queue redelivery on async).
        verify(mockChatService, times(1)).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
    }

    @Test
    void citationGuard_AcceptsDeliberateNoEvidenceRefusal() throws ChatServiceException {
        final String userQuery = "query";
        final String userQueryPrompt = "prompt";
        final List<ChunkedEntry> chunkedEntries = stubbedChunks(userQuery, userQueryPrompt);

        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.of("refusal raw"));
        when(citationProcessor.processCitations("refusal raw"))
                .thenReturn(new CitationOutcome("No relevant evidence found.", true, true, 0, 0, 0));

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(ANSWER_GENERATED, result.status());
        assertEquals("No relevant evidence found.", result.formattedLlmResponse());
        verify(mockChatService, times(1)).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
    }

    @Test
    void citationGuard_Off_AcceptsUncitedAnswer() throws ChatServiceException {
        responseGenerationService = serviceWithGuard(CitationGuardMode.OFF);
        final String userQuery = "query";
        final String userQueryPrompt = "prompt";
        final List<ChunkedEntry> chunkedEntries = stubbedChunks(userQuery, userQueryPrompt);

        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.of("uncited raw"));
        when(citationProcessor.processCitations("uncited raw")).thenReturn(degradedOutcome("uncited formatted"));

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(ANSWER_GENERATED, result.status());
        assertEquals("uncited formatted", result.formattedLlmResponse());
        verify(mockChatService, times(1)).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
    }
}
