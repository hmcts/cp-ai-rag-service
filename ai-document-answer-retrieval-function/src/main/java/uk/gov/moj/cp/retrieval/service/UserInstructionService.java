package uk.gov.moj.cp.retrieval.service;

public class UserInstructionService {

    private static final String USER_INSTRUCTION_TEMPLATE = """
            --- SOURCE DOCUMENTS ---
            %s
            
            --- USER QUERY INSTRUCTION ---
            %s
            
            --- USER QUERY ---
            %s
            """;

    public String buildUserInstruction(final String userQuery, final String userQueryPrompt, final String formattedChunks) {
        return String.format(USER_INSTRUCTION_TEMPLATE, formattedChunks, userQueryPrompt != null ? userQueryPrompt : "", userQuery);
    }
}
