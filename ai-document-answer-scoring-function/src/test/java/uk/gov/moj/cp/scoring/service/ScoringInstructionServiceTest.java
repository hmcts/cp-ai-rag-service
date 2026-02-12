package uk.gov.moj.cp.scoring.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoringInstructionServiceTest {

    private final ScoringInstructionService scoringInstructionService = new ScoringInstructionService();

    @Test
    void buildsUserInstructionWithAllInputsProvided() {
        String userQuery = "What is the capital of France?";
        String userQueryPrompt = "Provide a detailed answer.";
        String formattedChunks = "Document 1: Paris is the capital of France.";
        String llmResponse = "The capital of France is Paris.";

        String result = scoringInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks, llmResponse);

        String expected = """
                --- SOURCE DOCUMENTS ---
                Document 1: Paris is the capital of France.
                
                --- ORIGINAL USER QUERY ---
                What is the capital of France?
                
                --- USER QUERY INSTRUCTION ---
                Provide a detailed answer.
                
                --- ANSWER TO EVALUATE ---
                The capital of France is Paris.
                
                Evaluate the answer now.
                """;

        assertEquals(expected, result);
    }

    @Test
    void buildsUserInstructionWithNullUserQueryPrompt() {
        String userQuery = "What is the capital of France?";
        String userQueryPrompt = null;
        String formattedChunks = "Document 1: Paris is the capital of France.";
        String llmResponse = "The capital of France is Paris.";

        String result = scoringInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks, llmResponse);

        String expected = """
                --- SOURCE DOCUMENTS ---
                Document 1: Paris is the capital of France.
                
                --- ORIGINAL USER QUERY ---
                What is the capital of France?
                
                --- USER QUERY INSTRUCTION ---
                
                
                --- ANSWER TO EVALUATE ---
                The capital of France is Paris.
                
                Evaluate the answer now.
                """;

        assertEquals(expected, result);
    }

    @Test
    void buildsUserInstructionWithEmptyFormattedChunks() {
        String userQuery = "What is the capital of France?";
        String userQueryPrompt = "Provide a detailed answer.";
        String formattedChunks = "";
        String llmResponse = "The capital of France is Paris.";

        String result = scoringInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks, llmResponse);

        String expected = """
                --- SOURCE DOCUMENTS ---
                
                
                --- ORIGINAL USER QUERY ---
                What is the capital of France?
                
                --- USER QUERY INSTRUCTION ---
                Provide a detailed answer.
                
                --- ANSWER TO EVALUATE ---
                The capital of France is Paris.
                
                Evaluate the answer now.
                """;

        assertEquals(expected, result);
    }

    @Test
    void buildsUserInstructionWithEmptyLlmResponse() {
        String userQuery = "What is the capital of France?";
        String userQueryPrompt = "Provide a detailed answer.";
        String formattedChunks = "Document 1: Paris is the capital of France.";
        String llmResponse = "";

        String result = scoringInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks, llmResponse);

        String expected = """
                --- SOURCE DOCUMENTS ---
                Document 1: Paris is the capital of France.
                
                --- ORIGINAL USER QUERY ---
                What is the capital of France?
                
                --- USER QUERY INSTRUCTION ---
                Provide a detailed answer.
                
                --- ANSWER TO EVALUATE ---
                
                
                Evaluate the answer now.
                """;

        assertEquals(expected, result);
    }
}
