package uk.gov.moj.cp.scoring;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.scoring.service.ScoringService;

import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;

/**
 * Azure Function for answer scoring and telemetry.
 * Scores generated responses and records telemetry in Azure Monitor.
 */
public class AnswerScoringFunction {

    private static final Logger LOGGER = Logger.getLogger(AnswerScoringFunction.class.getName());

    private ScoringService scoringService;

    public AnswerScoringFunction() {
        scoringService = new ScoringService();
    }

    AnswerScoringFunction(ScoringService scoringService) {
        this.scoringService = scoringService;
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
                    queueName = "answer-scoring-queue",
                    connection = "AzureWebJobsStorage"
            ) String message,
            final ExecutionContext context) {

        try {
            final QueryResponse queryResponse = new ObjectMapper().readValue(message, QueryResponse.class);

            LOGGER.log(INFO, () -> "Starting process to score answer for query: " + queryResponse.userQuery());

            final ModelScore modelScore = scoringService.evaluateGroundedness(queryResponse.llmResponse(), queryResponse.userQuery(), queryResponse.chunkedEntries());

            LOGGER.log(INFO, () -> "Answer scoring processing completed successfully for message with score : " + modelScore.groundednessScore());

        } catch (Exception e) {
            LOGGER.log(SEVERE, e, () -> "Error processing answer scoring for message: " + message);
            try {
                throw e;
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
