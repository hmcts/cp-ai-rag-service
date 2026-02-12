package uk.gov.moj.cp.scoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;
import static uk.gov.moj.cp.scoring.service.ScoringService.JUDGE_LLM_SYSTEM_INSTRUCTIONS;

import uk.gov.moj.cp.ai.exception.ChatServiceException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.scoring.model.ModelScore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock
    private ChatService chatServiceMock;

    @Mock
    private ChunkFormatterUtility chunkFormatterUtilityMock;

    @Mock
    private ScoringInstructionService scoringInstructionServiceMock;

    @Mock
    private List<ChunkedEntry> chunkedEntriesMock;

    private final String dummyUserQuery = "Dummy user query";
    private final String dummyQueryPrompt = "Dummy query prompt";
    private final String dummyLlmResponse = "Dummy LLM response";
    private final String mockUserInstructions = "Mock user instructions";
    private final String mockFormattedChunks = "Mock formatted chunks";

    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new ScoringService(chatServiceMock, scoringInstructionServiceMock, chunkFormatterUtilityMock);
    }

    @Test
    @DisplayName("Returns parsed score when chat service provides valid response")
    void returnsParsedScoreWhenChatServiceProvidesValidResponse() throws ChatServiceException {
        ModelScore expected = new ModelScore(new BigDecimal("4.5"), "Valid reasoning");
        when(chunkFormatterUtilityMock.buildChunkContext(chunkedEntriesMock)).thenReturn(mockFormattedChunks);
        when(scoringInstructionServiceMock.buildUserInstruction(dummyUserQuery, dummyQueryPrompt, mockFormattedChunks, dummyLlmResponse)).thenReturn(mockUserInstructions);
        when(chatServiceMock.callModel(eq(JUDGE_LLM_SYSTEM_INSTRUCTIONS), eq(mockUserInstructions), eq(ModelScore.class)))
                .thenReturn(Optional.of(expected));

        ModelScore result = scoringService.evaluateGroundedness(dummyLlmResponse, dummyUserQuery, dummyQueryPrompt, chunkedEntriesMock);

        assertEquals(new BigDecimal("4.5"), result.groundednessScore());
        assertEquals("Valid reasoning", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when chat service returns empty response")
    void returnsDefaultScoreWhenChatServiceReturnsEmptyResponse() throws ChatServiceException {
        when(chunkFormatterUtilityMock.buildChunkContext(chunkedEntriesMock)).thenReturn(mockFormattedChunks);
        when(scoringInstructionServiceMock.buildUserInstruction(dummyUserQuery, dummyQueryPrompt, mockFormattedChunks, dummyLlmResponse)).thenReturn(mockUserInstructions);
        when(chatServiceMock.callModel(eq(JUDGE_LLM_SYSTEM_INSTRUCTIONS), eq(mockUserInstructions), eq(ModelScore.class)))
                .thenReturn(Optional.empty());

        ModelScore result = scoringService.evaluateGroundedness(dummyLlmResponse, dummyUserQuery, dummyQueryPrompt, chunkedEntriesMock);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Handles null response from chat service and returns default score")
    void handlesNullResponseFromChatServiceAndReturnsDefaultScore() throws ChatServiceException {
        when(chunkFormatterUtilityMock.buildChunkContext(chunkedEntriesMock)).thenReturn(mockFormattedChunks);
        when(scoringInstructionServiceMock.buildUserInstruction(dummyUserQuery, dummyQueryPrompt, mockFormattedChunks, dummyLlmResponse)).thenReturn(mockUserInstructions);
        when(chatServiceMock.callModel(eq(JUDGE_LLM_SYSTEM_INSTRUCTIONS), eq(mockUserInstructions), eq(ModelScore.class)))
                .thenReturn(null);

        ModelScore result = scoringService.evaluateGroundedness(dummyLlmResponse, dummyUserQuery, dummyQueryPrompt, chunkedEntriesMock);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Logs error and returns default score when chat service throws exception")
    void logsErrorAndReturnsDefaultScoreWhenChatServiceThrowsException() throws ChatServiceException {
        when(chunkFormatterUtilityMock.buildChunkContext(chunkedEntriesMock)).thenReturn(mockFormattedChunks);
        when(scoringInstructionServiceMock.buildUserInstruction(dummyUserQuery, dummyQueryPrompt, mockFormattedChunks, dummyLlmResponse)).thenReturn(mockUserInstructions);
        when(chatServiceMock.callModel(eq(JUDGE_LLM_SYSTEM_INSTRUCTIONS), eq(mockUserInstructions), eq(ModelScore.class)))
                .thenThrow(new RuntimeException("Chat service error"));

        ModelScore result = scoringService.evaluateGroundedness(dummyLlmResponse, dummyUserQuery, dummyQueryPrompt, chunkedEntriesMock);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }
}
