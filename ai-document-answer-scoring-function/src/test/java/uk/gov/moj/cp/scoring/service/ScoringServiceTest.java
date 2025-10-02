package uk.gov.moj.cp.scoring.service;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.scoring.model.ModelScore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoringServiceTest {

    private ChatService chatServiceMock;
    private ScoringService scoringService;

    @BeforeEach
    void setUp() {
        chatServiceMock = mock(ChatService.class);
        scoringService = new ScoringService(chatServiceMock);
    }

    @Test
    @DisplayName("Returns parsed score when chat service provides valid response")
    void returnsParsedScoreWhenChatServiceProvidesValidResponse() {
        ModelScore expected = new ModelScore(new BigDecimal("4.5"), "Valid reasoning");
        when(chatServiceMock.callModel(anyString(), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenReturn(Optional.of(expected));

        ModelScore result = scoringService.evaluateGroundedness("response", "query", List.of());

        assertEquals(new BigDecimal("4.5"), result.groundednessScore());
        assertEquals("Valid reasoning", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when retrieved documents are null")
    void returnsDefaultScoreWhenRetrievedDocumentsAreNull() {
        ModelScore result = scoringService.evaluateGroundedness("response", "query", null);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when retrieved documents list is empty")
    void returnsDefaultScoreWhenRetrievedDocumentsListIsEmpty() {
        ModelScore result = scoringService.evaluateGroundedness("response", "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when chat service returns empty response")
    void returnsDefaultScoreWhenChatServiceReturnsEmptyResponse() {
        when(chatServiceMock.callModel(anyString(), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenReturn(Optional.empty());

        ModelScore result = scoringService.evaluateGroundedness("response", "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Handles null response from chat service and returns default score")
    void handlesNullResponseFromChatServiceAndReturnsDefaultScore() {
        when(chatServiceMock.callModel(anyString(), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenReturn(null);

        ModelScore result = scoringService.evaluateGroundedness("response", "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Logs error and returns default score when chat service throws exception")
    void logsErrorAndReturnsDefaultScoreWhenChatServiceThrowsException() {
        when(chatServiceMock.callModel(anyString(), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenThrow(new RuntimeException("Chat service error"));

        ModelScore result = scoringService.evaluateGroundedness("response", "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when retrieved documents contain empty chunks")
    void returnsDefaultScoreWhenRetrievedDocumentsContainEmptyChunks() {
        List<ChunkedEntry> retrievedDocuments = List.of(
                new ChunkedEntry(randomUUID().toString(), "", "doc1", 1, randomUUID().toString()),
                new ChunkedEntry(randomUUID().toString(), "", "doc2", 2, randomUUID().toString())
        );

        ModelScore result = scoringService.evaluateGroundedness("response", "query", retrievedDocuments);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when retrieved documents contain null chunks")
    void returnsDefaultScoreWhenRetrievedDocumentsContainNullChunks() {
        List<ChunkedEntry> retrievedDocuments = List.of(
                new ChunkedEntry(randomUUID().toString(), null, "doc1", 1, randomUUID().toString()),
                new ChunkedEntry(randomUUID().toString(), null, "doc2", 2, randomUUID().toString())
        );

        ModelScore result = scoringService.evaluateGroundedness("response", "query", retrievedDocuments);

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when system prompt instruction is empty")
    void returnsDefaultScoreWhenSystemPromptInstructionIsEmpty() {
        when(chatServiceMock.callModel(eq(""), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenReturn(Optional.empty());

        ModelScore result = scoringService.evaluateGroundedness("", "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }

    @Test
    @DisplayName("Returns default score when system prompt instruction is null")
    void returnsDefaultScoreWhenSystemPromptInstructionIsNull() {
        when(chatServiceMock.callModel(isNull(), eq("Evaluate the answer."), eq(ModelScore.class)))
                .thenReturn(Optional.empty());

        ModelScore result = scoringService.evaluateGroundedness(null, "query", List.of());

        assertEquals(BigDecimal.ZERO, result.groundednessScore());
        assertEquals("Error generating score", result.reasoning());
    }
}
