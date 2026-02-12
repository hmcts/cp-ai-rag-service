package uk.gov.moj.cp.scoring;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
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
    private final AnswerGenerationTableService answerGenerationTableService;

    public AnswerScoringFunction() {
        scoringService = new ScoringService();
        publishScoreService = new PublishScoreService();
        blobService = new BlobService();
        answerGenerationTableService = new AnswerGenerationTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION));
    }

    AnswerScoringFunction(final ScoringService scoringService, final PublishScoreService publishScoreService, final BlobService blobService, final AnswerGenerationTableService answerGenerationTableService) {
        this.scoringService = scoringService;
        this.publishScoreService = publishScoreService;
        this.blobService = blobService;
        this.answerGenerationTableService = answerGenerationTableService;

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
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) String message,
            final ExecutionContext context) {

        try {
            final ScoringQueuePayload queuePayload = getObjectMapper().readValue(message, ScoringQueuePayload.class);

            final ScoringPayload scoringPayload = blobService.readBlob(queuePayload.filename(), ScoringPayload.class);

            LOGGER.info("Starting process to score answer for transactionId '{}' and query '{}'", scoringPayload.transactionId(), scoringPayload.userQuery());

            final ModelScore modelScore = scoringService.evaluateGroundedness(scoringPayload.llmResponse(), scoringPayload.userQuery(), scoringPayload.queryPrompt(), scoringPayload.chunkedEntries());

            LOGGER.info("Score now available for the answer : {}", modelScore.groundednessScore());

            publishScoreService.publishGroundednessScore(modelScore.groundednessScore(), scoringPayload.userQuery());

            if(null != scoringPayload.transactionId()) {
                LOGGER.info("Recording groundedness score against transaction id: {}", scoringPayload.transactionId());
                answerGenerationTableService.recordGroundednessScore(scoringPayload.transactionId(), modelScore.groundednessScore());
            }

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
