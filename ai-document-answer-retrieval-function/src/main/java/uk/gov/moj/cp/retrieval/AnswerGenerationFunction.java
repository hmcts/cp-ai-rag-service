package uk.gov.moj.cp.retrieval;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationStatus;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queue-triggered Azure Function for answer generation.
 */
public class AnswerGenerationFunction {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnswerGenerationFunction.class);
    public static final String LLM_ANSWER_WITH_CHUNKS = "llm-answer-with-chunks-%s.json";
    static final String LLM_INPUT_CHUNKS = "llm-input-chunks-%s.json";

    private final EmbedDataService embedDataService;
    private final AzureAISearchService searchService;
    private final ResponseGenerationService responseGenerationService;
    private final BlobPersistenceService blobPersistenceEvalPayloadsService;
    private final BlobPersistenceService blobPersistenceInputChunksService;
    private final AnswerGenerationTableService answerGenerationTableService;

    public AnswerGenerationFunction() {
        this.embedDataService = new EmbedDataService();
        this.searchService = new AzureAISearchService();
        this.responseGenerationService = new ResponseGenerationService();
        this.blobPersistenceEvalPayloadsService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS));
        this.blobPersistenceInputChunksService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS));
        this.answerGenerationTableService =
                new AnswerGenerationTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION));
    }

    public AnswerGenerationFunction(
            EmbedDataService embedDataService,
            AzureAISearchService searchService,
            ResponseGenerationService responseGenerationService,
            BlobPersistenceService blobPersistenceEvalPayloadsService,
            BlobPersistenceService blobPersistenceInputChunksService,
            AnswerGenerationTableService answerGenerationTableService
    ) {
        this.embedDataService = embedDataService;
        this.searchService = searchService;
        this.responseGenerationService = responseGenerationService;
        this.blobPersistenceEvalPayloadsService = blobPersistenceEvalPayloadsService;
        this.blobPersistenceInputChunksService = blobPersistenceInputChunksService;
        this.answerGenerationTableService = answerGenerationTableService;
    }


    @FunctionName("AnswerGeneration")
    public void run(
            @QueueTrigger(
                    name = "queueMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) final String queueMessage,

            @QueueOutput(
                    name = "scoringMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) final OutputBinding<String> scoringMessage
    ) {

        long startTime = currentTimeMillis();
        AnswerGenerationQueuePayload payload = null;

        try {
            if (isNullOrEmpty(queueMessage)) {
                throw new IllegalArgumentException("Queue message is empty");
            }

            payload = getObjectMapper().readValue(queueMessage, AnswerGenerationQueuePayload.class);

            // Validation
            if (isNull(payload.transactionId())
                    || isNullOrEmpty(payload.userQuery())
                    || isNullOrEmpty(payload.queryPrompt())
                    || isNull(payload.metadataFilter())
                    || payload.metadataFilter().isEmpty()) {

                LOGGER.error("Minimal mandatory data not available for processing: {}", payload);
                return;
            }

            final UUID transactionId = payload.transactionId();

            LOGGER.info("Starting answer generation for transactionId '{}'", transactionId);

            final List<Float> embeddings = embedDataService.getEmbedding(payload.userQuery());
            final List<ChunkedEntry> chunkedEntries = searchService.search(payload.userQuery(), embeddings, payload.metadataFilter());
            final String llmResponse = responseGenerationService.generateResponse(payload.userQuery(), chunkedEntries, payload.queryPrompt());

            final String inputChunksFilename = saveInputChunksToTheBlobContainer(transactionId, chunkedEntries);

            final long durationMs = currentTimeMillis() - startTime;

            answerGenerationTableService.upsertIntoTable(
                    transactionId.toString(),
                    payload.userQuery(),
                    payload.queryPrompt(),
                    inputChunksFilename,
                    llmResponse,
                    AnswerGenerationStatus.ANSWER_GENERATED,
                    null,
                    OffsetDateTime.now(),
                    durationMs
            );

            // Persist blob + scoring queue
            final String filename = getAnswerWithChunksFilename(transactionId);
            final ScoringPayload scoringPayload = new ScoringPayload(payload.userQuery(),
                    llmResponse, payload.queryPrompt(), chunkedEntries, transactionId.toString());
            saveLlmResponseToTheBlobContainer(filename, scoringPayload);

            scoringMessage.setValue(getObjectMapper().writeValueAsString(new ScoringQueuePayload(filename)));
            LOGGER.info("Answer generation completed for transactionId={} in {} ms", transactionId, durationMs);

        } catch (Exception e) {
            final long durationMs = currentTimeMillis() - startTime;

            LOGGER.error("Answer generation failed", e);

            if (nonNull(payload) && nonNull(payload.transactionId())) {
                answerGenerationTableService.upsertIntoTable(
                        payload.transactionId().toString(),
                        payload.userQuery(),
                        payload.queryPrompt(),
                        null,
                        null,
                        AnswerGenerationStatus.ANSWER_GENERATION_FAILED,
                        e.getMessage(),
                        OffsetDateTime.now(),
                        durationMs
                );
            }

        }
    }

    private void saveLlmResponseToTheBlobContainer(final String filename, final ScoringPayload scoringPayload) throws JsonProcessingException {
        blobPersistenceEvalPayloadsService.saveBlob(filename, getObjectMapper().writeValueAsString(scoringPayload));
    }

    private String saveInputChunksToTheBlobContainer(final UUID transactionId, final List<ChunkedEntry> chunkedEntries) throws JsonProcessingException {
        final String inputChunksFilename = getInputChunksFilename(transactionId);
        final InputChunksPayload inputChunksPayload = new InputChunksPayload(chunkedEntries, transactionId);

        blobPersistenceInputChunksService.saveBlob(inputChunksFilename, getObjectMapper().writeValueAsString(inputChunksPayload));
        return inputChunksFilename;
    }

    private static String getAnswerWithChunksFilename(final UUID transactionId) {
        return format(LLM_ANSWER_WITH_CHUNKS, transactionId);
    }

    public static String getInputChunksFilename(final UUID transactionId) {
        return format(LLM_INPUT_CHUNKS, transactionId);
    }
}
