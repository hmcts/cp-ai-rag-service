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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.retrieval.service.ResponseGenerationService.LLM_RESPONSE_FAILURE_TO_GENERATE;
import static uk.gov.moj.cp.retrieval.service.ResponseGenerationService.LLM_RESPONSE_NO_DATA_AVAILABLE;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.exception.ResponseGenerationServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.retrieval.model.LlmResponse;

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

    @BeforeEach
    void setUp() {
        openMocks(this);
        responseGenerationService = new ResponseGenerationService(mockChatService, citationProcessor, chunkFormatterUtility, userInstructionService, mockSystemPromptTemplate);
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


        when(citationProcessor.processAndFormatCitations(mockRawLlmResponse)).thenReturn(mockFormattedLlmResponse);
        when(chunkFormatterUtility.buildChunkContext(chunkedEntries)).thenReturn(mockFormattedChunk);
        when(userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk)).thenReturn(mockUserInstructions);
        when(mockChatService.callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class)))
                .thenReturn(Optional.of(mockRawLlmResponse));

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(mockFormattedLlmResponse, result.formattedLlmResponse());
        assertEquals(mockRawLlmResponse, result.rawLlmResponse());
        assertEquals(ANSWER_GENERATED, result.status());
        verify(citationProcessor).processAndFormatCitations(mockRawLlmResponse);
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

        verify(mockChatService).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
        verify(citationProcessor, never()).processAndFormatCitations(any());
        verify(chunkFormatterUtility).buildChunkContext(chunkedEntries);
        verify(userInstructionService).buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk);
    }

    @Test
    void generateResponse_ReturnsErrorMessage_WhenChatServiceThrowsChatServiceException() throws ChatServiceException {
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
                .thenThrow(new ChatServiceException("Service error"));

        final LlmResponse result = responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt);

        assertEquals(LLM_RESPONSE_FAILURE_TO_GENERATE, result.rawLlmResponse());
        assertEquals(LLM_RESPONSE_FAILURE_TO_GENERATE, result.formattedLlmResponse());
        assertEquals(ANSWER_GENERATION_FAILED, result.status());

        verify(mockChatService).callModel(eq(mockSystemPromptTemplate), eq(mockUserInstructions), eq(String.class));
        verify(citationProcessor, never()).processAndFormatCitations(any());
        verify(chunkFormatterUtility).buildChunkContext(chunkedEntries);
        verify(userInstructionService).buildUserInstruction(userQuery, userQueryPrompt, mockFormattedChunk);
    }

    @Test
    void generateResponse_ThrowsResponseGenerationServiceException_WhenChatServiceThrowsNonChatServiceException() throws ChatServiceException {
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

        ResponseGenerationServiceException ex = assertThrows(
                ResponseGenerationServiceException.class,
                () -> responseGenerationService.generateResponse(userQuery, chunkedEntries, userQueryPrompt)
        );

        assertThat(ex.getMessage().contains(userQuery), is(true));
        assertThat(ex.getCause() instanceof HttpResponseException, is(true));
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
}
