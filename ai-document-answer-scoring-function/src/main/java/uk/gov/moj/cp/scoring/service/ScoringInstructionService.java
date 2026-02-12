package uk.gov.moj.cp.scoring.service;

public class ScoringInstructionService {

    private static final String USER_INSTRUCTION_TEMPLATE = """
            --- SOURCE DOCUMENTS ---
            %s
            
            --- ORIGINAL USER QUERY ---
            %s
            
            --- USER QUERY INSTRUCTION ---
            %s
            
            --- ANSWER TO EVALUATE ---
            %s
            
            Evaluate the answer now.
            """;

    public String buildUserInstruction(final String userQuery, final String userQueryPrompt, final String formattedChunks, final String llmResponse) {
        return String.format(USER_INSTRUCTION_TEMPLATE, formattedChunks, userQuery, userQueryPrompt != null ? userQueryPrompt : "", llmResponse);
    }
}
