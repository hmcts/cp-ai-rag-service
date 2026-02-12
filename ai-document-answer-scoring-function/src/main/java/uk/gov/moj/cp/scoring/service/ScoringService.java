package uk.gov.moj.cp.scoring.service;

import uk.gov.moj.cp.ai.coverage.Generated;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.service.ChatService;
import uk.gov.moj.cp.ai.util.ChunkFormatterUtility;
import uk.gov.moj.cp.scoring.model.ModelScore;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScoringService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringService.class);

    static final String JUDGE_LLM_SYSTEM_INSTRUCTIONS = """
            You are an expert evaluator. Your task is to rate the groundedness of an answer based on a set of provided documents.
            Groundedness is defined as the degree to which every claim in the answer is directly and explicitly supported by the documents.
            Use a rating scale from 1 to 5, where:
            1: None of the claims in the answer are supported by the documents.
            2: A small portion of the claims are supported.
            3: Some of the claims are supported, but there are significant ungrounded statements.
            4: Most of the claims are supported, with only minor, ungrounded statements.
            5: Every claim in the answer is explicitly and directly supported by the documents.
            
            Your output MUST be a JSON object with the following keys:
            {
              "groundednessScore": <Your numeric score 1-5>,
              "reasoning": "<A brief explanation for the score>"
            }
            """;

    private final ChatService chatService;
    private final ChunkFormatterUtility chunkFormatterUtility;
    private final ScoringInstructionService scoringInstructionService;

    @Generated
    public ScoringService() {
        String judgeModelEndpoint = System.getenv("AZURE_JUDGE_OPENAI_ENDPOINT");
        String judgeChatDeploymentName = System.getenv("AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME");
        chatService = new ChatService(judgeModelEndpoint, judgeChatDeploymentName);
        chunkFormatterUtility = new ChunkFormatterUtility();
        scoringInstructionService = new ScoringInstructionService();
    }

    ScoringService(final ChatService chatService, final ScoringInstructionService scoringInstructionService, final ChunkFormatterUtility chunkFormatterUtility) {
        this.chatService = chatService;
        this.chunkFormatterUtility = chunkFormatterUtility;
        this.scoringInstructionService = scoringInstructionService;
    }

    /**
     * Evaluates the groundedness of an LLM response using a Judge LLM. The Judge LLM is prompted to
     * score the response based on the provided context.
     *
     * @param llmResponse        The response from the generator LLM.
     * @param userQuery          The original user query.
     * @param retrievedDocuments The documents retrieved from search.
     * @return The groundedness score from the judge, or a default value on error.
     */
    public ModelScore evaluateGroundedness(final String llmResponse, final String userQuery, final String queryPrompt, final List<ChunkedEntry> retrievedDocuments) {
        LOGGER.info("Evaluating groundedness of response...");

        final String formattedChunks = chunkFormatterUtility.buildChunkContext(retrievedDocuments);
        final String userInstruction = scoringInstructionService.buildUserInstruction(userQuery, queryPrompt, formattedChunks, llmResponse);

        try {
            return chatService.callModel(JUDGE_LLM_SYSTEM_INSTRUCTIONS, userInstruction, ModelScore.class)
                    .orElse(new ModelScore(BigDecimal.ZERO, "Error generating score"));
        } catch (Exception e) {
            LOGGER.error("Error calling Judge LLM for evaluation", e);
            return new ModelScore(BigDecimal.ZERO, "Error generating score");
        }
    }
}