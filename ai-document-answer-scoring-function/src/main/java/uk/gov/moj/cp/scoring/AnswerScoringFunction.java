package uk.gov.moj.cp.scoring;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_QUEUE_STORAGE_ENDPOINT;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_NAME;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.scoring.exception.BlobParsingException;
import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.BlobService;
import uk.gov.moj.cp.scoring.service.PublishScoreService;
import uk.gov.moj.cp.scoring.service.ScoringService;

import com.fasterxml.jackson.core.JsonProcessingException;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerScoringFunction.class);

    private final ScoringService scoringService;
    private final PublishScoreService publishScoreService;
    private final BlobService blobService;

    public AnswerScoringFunction() {
        scoringService = new ScoringService();
        publishScoreService = new PublishScoreService();
        blobService = new BlobService();
    }

    AnswerScoringFunction(final ScoringService scoringService, final PublishScoreService publishScoreService, final BlobService blobService) {
        this.scoringService = scoringService;
        this.publishScoreService = publishScoreService;
        this.blobService = blobService;
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
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_NAME
            ) String message,
            final ExecutionContext context) {

        try {
            final ScoringQueuePayload queuePayload = getObjectMapper().readValue(message, ScoringQueuePayload.class);

            final QueryResponse judgeLlmInput = blobService.readBlob(queuePayload.filename());

            LOGGER.info("Starting process to score answer for query: {}", judgeLlmInput.userQuery());

            final ModelScore modelScore = scoringService.evaluateGroundedness(judgeLlmInput.llmResponse(), judgeLlmInput.userQuery(), judgeLlmInput.chunkedEntries());

            LOGGER.info("Score now available for the answer : {}", modelScore.groundednessScore());

            publishScoreService.publishGroundednessScore(modelScore.groundednessScore(), judgeLlmInput.userQuery());

            LOGGER.info("Answer scoring processing completed successfully for message with score : {}", modelScore.groundednessScore());

        } catch (Exception e) {
            try {
                throw e;
            } catch (JsonProcessingException | BlobParsingException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
