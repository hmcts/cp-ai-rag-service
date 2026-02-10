package uk.gov.moj.cp.retrieval.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserInstructionServiceTest {

    private final UserInstructionService userInstructionService = new UserInstructionService();

    @Test
    void buildUserInstruction_ReturnsFormattedString_WhenAllInputsProvided() {
        String userQuery = "What is the legal status?";
        String userQueryPrompt = "Provide a detailed answer.";
        String formattedChunks = "<RETRIEVED_DOCUMENTS>\n...</RETRIEVED_DOCUMENTS>";
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
        String userQuery = "What is the legal status?";
        String userQueryPrompt = null;
        String formattedChunks = "<RETRIEVED_DOCUMENTS>\n...</RETRIEVED_DOCUMENTS>";
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
        String userQuery = "What is the legal status?";
        String userQueryPrompt = "";
        String formattedChunks = "<RETRIEVED_DOCUMENTS>\n...</RETRIEVED_DOCUMENTS>";
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
        String userQuery = "What is the legal status?";
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

