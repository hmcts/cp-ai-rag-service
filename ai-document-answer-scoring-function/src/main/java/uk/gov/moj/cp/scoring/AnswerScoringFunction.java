package uk.gov.moj.cp.scoring;

import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.PublishScoreService;
import uk.gov.moj.cp.scoring.service.ScoringService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for answer scoring and telemetry.
 * Scores generated responses and records telemetry in Azure Monitor.
 */
public class AnswerScoringFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerScoringFunction.class.getName());

    private final ScoringService scoringService;
    private final PublishScoreService publishScoreService;

    public AnswerScoringFunction() {
        scoringService = new ScoringService();
        publishScoreService = new PublishScoreService();
    }

    AnswerScoringFunction(ScoringService scoringService, PublishScoreService publishScoreService) {
        this.scoringService = scoringService;
        this.publishScoreService = publishScoreService;
    }

    /**
     * Function triggered by queue messages for answer scoring.
     *
     * @param message The queue message containing answer scoring information
     * @param context The execution context
     */
    @FunctionName("AnswerScoring")
    public void run(
            @QueueTrigger(
                    name = "message",
                    queueName = "%STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING%",
                    connection = "AI_RAG_SERVICE_STORAGE_ACCOUNT"
            ) String message,
            final ExecutionContext context) {

        try {
            final QueryResponse queryResponse = new ObjectMapper().readValue(message, QueryResponse.class);

            LOGGER.info("Starting process to score answer for query: {}", queryResponse.userQuery());

            final ModelScore modelScore = scoringService.evaluateGroundedness(queryResponse.llmResponse(), queryResponse.userQuery(), queryResponse.chunkedEntries());

            LOGGER.info("Score now available for the answer : {}", modelScore.groundednessScore());

            publishScoreService.publishGroundednessScore(modelScore.groundednessScore(), queryResponse.userQuery());

            LOGGER.info("Answer scoring processing completed successfully for message with score : {}", modelScore.groundednessScore());

        } catch (Exception e) {
            LOGGER.error("Error processing answer scoring for message: " + message, e);
            try {
                throw e;
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
