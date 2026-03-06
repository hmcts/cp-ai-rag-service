package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserInstructionServiceTest {

    private final UserInstructionService userInstructionService = new UserInstructionService();
    private final String userQuery = "What is the legal status?";
    private final String formattedChunks = "<RETRIEVED_DOCUMENTS>\n...</RETRIEVED_DOCUMENTS>";

    @Test
    void buildUserInstruction_ReturnsFormattedString_WhenAllInputsProvided() {
        String userQueryPrompt = "Provide a detailed answer.";
        String result = userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks);
        String expected = """
                --- SOURCE DOCUMENTS ---
                <RETRIEVED_DOCUMENTS>
                ...</RETRIEVED_DOCUMENTS>
                
                --- USER QUERY INSTRUCTION ---
                Provide a detailed answer.
                
                --- USER QUERY ---
                What is the legal status?
                """;
        assertEquals(expected, result);
    }

    @Test
    void buildUserInstruction_HandlesNullUserQueryPrompt() {
        String userQueryPrompt = null;
        String result = userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks);
        String expected = """
                --- SOURCE DOCUMENTS ---
                <RETRIEVED_DOCUMENTS>
                ...</RETRIEVED_DOCUMENTS>
                
                --- USER QUERY INSTRUCTION ---
                
                
                --- USER QUERY ---
                What is the legal status?
                """;
        assertEquals(expected, result);
    }

    @Test
    void buildUserInstruction_HandlesEmptyUserQueryPrompt() {
        String userQueryPrompt = "";
        String result = userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks);
        String expected = """
                --- SOURCE DOCUMENTS ---
                <RETRIEVED_DOCUMENTS>
                ...</RETRIEVED_DOCUMENTS>
                
                --- USER QUERY INSTRUCTION ---
                
                
                --- USER QUERY ---
                What is the legal status?
                """;
        assertEquals(expected, result);
    }

    @Test
    void buildUserInstruction_HandlesEmptyFormattedChunks() {
        String userQueryPrompt = "Prompt";
        String formattedChunks = "";
        String result = userInstructionService.buildUserInstruction(userQuery, userQueryPrompt, formattedChunks);
        String expected = """
                --- SOURCE DOCUMENTS ---
                
                
                --- USER QUERY INSTRUCTION ---
                Prompt
                
                --- USER QUERY ---
                What is the legal status?
                """;
        assertEquals(expected, result);
    }
}

