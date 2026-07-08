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
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATED;
import static uk.gov.hmcts.cp.openapi.model.AnswerGenerationStatus.ANSWER_GENERATION_FAILED;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getAnswerWithChunksFilename;
import static uk.gov.moj.cp.retrieval.util.ChunkUtil.getInputChunksFilename;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.BindingName;
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

    private final EmbedDataService embedDataService;
    private final AzureAISearchService searchService;
    private final ResponseGenerationService responseGenerationService;
    private final BlobPersistenceService blobPersistenceEvalPayloadsService;
    private final BlobPersistenceService blobPersistenceInputChunksService;
    private final AnswerGenerationTableService answerGenerationTableService;
    private final CitationGuardMode guardMode;

    public AnswerGenerationFunction() {
        this.embedDataService = new EmbedDataService();
        this.searchService = new AzureAISearchService();
        this.responseGenerationService = new ResponseGenerationService();

        this.blobPersistenceEvalPayloadsService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS));
        this.blobPersistenceInputChunksService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS));
        this.answerGenerationTableService = new AnswerGenerationTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION));
        this.guardMode = CitationGuardMode.fromEnv();
    }

    public AnswerGenerationFunction(
            EmbedDataService embedDataService,
            AzureAISearchService searchService,
            ResponseGenerationService responseGenerationService,
            BlobPersistenceService blobPersistenceEvalPayloadsService,
            BlobPersistenceService blobPersistenceInputChunksService,
            AnswerGenerationTableService answerGenerationTableService
    ) {
        this(embedDataService, searchService, responseGenerationService, blobPersistenceEvalPayloadsService,
                blobPersistenceInputChunksService, answerGenerationTableService, CitationGuardMode.fromEnv());
    }

    AnswerGenerationFunction(
            EmbedDataService embedDataService,
            AzureAISearchService searchService,
            ResponseGenerationService responseGenerationService,
            BlobPersistenceService blobPersistenceEvalPayloadsService,
            BlobPersistenceService blobPersistenceInputChunksService,
            AnswerGenerationTableService answerGenerationTableService,
            CitationGuardMode guardMode
    ) {
        this.embedDataService = embedDataService;
        this.searchService = searchService;
        this.responseGenerationService = responseGenerationService;
        this.blobPersistenceEvalPayloadsService = blobPersistenceEvalPayloadsService;
        this.blobPersistenceInputChunksService = blobPersistenceInputChunksService;
        this.answerGenerationTableService = answerGenerationTableService;
        this.guardMode = guardMode;
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
            ) final OutputBinding<String> scoringMessage,
            @BindingName("DequeueCount") long dequeueCount,
            final ExecutionContext context
    ) {

        //defaultValue of maxDequeueCount should match the value in host.json
        final int maxDequeueCount = getRequiredEnvAsInteger("AzureFunctionsJobHost__extensions__queues__maxDequeueCount", "3");
        LOGGER.info("Event attempt count {} of {}", dequeueCount, maxDequeueCount);

        long startTime = currentTimeMillis();
        AnswerGenerationQueuePayload payload = null;
        List<ChunkedEntry> chunkedEntries = null;

        try {
            if (isNullOrEmpty(queueMessage)) {
                throw new IllegalArgumentException("Queue message is empty");
            }

            payload = getObjectMapper().readValue(queueMessage, AnswerGenerationQueuePayload.class);

            // Validation
            if (isNull(payload.transactionId()) || isNullOrEmpty(payload.userQuery())
                    || isNullOrEmpty(payload.queryPrompt()) || isNull(payload.metadataFilter())
                    || payload.metadataFilter().isEmpty()) {

                LOGGER.error("Minimal mandatory data not available for processing: {}", payload);
                return;
            }

            final UUID transactionId = payload.transactionId();

            LOGGER.info("Starting answer generation for transactionId '{}'", transactionId);

            final List<Float> embeddings = embedDataService.getEmbedding(payload.userQuery());
            chunkedEntries = searchService.search(payload.userQuery(), embeddings, payload.metadataFilter());
            final LlmResponse llmResponse = responseGenerationService.generateResponse(payload.userQuery(), chunkedEntries, payload.queryPrompt());

            persistAnswer(payload, llmResponse, chunkedEntries, currentTimeMillis() - startTime, scoringMessage);
        } catch (CitationDegradedException e) {
            // Citation-guard retry rides the queue redelivery mechanism: each redelivery is a
            // fresh, short invocation (re-embed, re-search, one LLM call) rather than a
            // long-running in-process loop.
            if (dequeueCount < maxDequeueCount) {
                throw redeliveryException(payload, " (citation-degraded)", e);
            }
            handleCitationExhaustion(payload, chunkedEntries, e, currentTimeMillis() - startTime, scoringMessage);
        } catch (Exception e) {
            if (dequeueCount == maxDequeueCount) {
                LOGGER.error("Answer generation failed", e);
                if (nonNull(payload) && nonNull(payload.transactionId())) {
                    recordAnswerGenerationFailed(payload, e.getMessage(), currentTimeMillis() - startTime);
                }
            } else {
                throw redeliveryException(payload, "", e);
            }
        }
    }

    /**
     * Persists one generated answer: input-chunks blob, status-table row (with the reason column
     * when there is one), then — unless the generation FAILED, in which case there is nothing
     * meaningful to score — the eval blob and the scoring enqueue. Shared by the happy path and
     * the citation-guard DELIVER branch.
     */
    private void persistAnswer(final AnswerGenerationQueuePayload payload,
                               final LlmResponse llmResponse,
                               final List<ChunkedEntry> chunkedEntries,
                               final long durationMs,
                               final OutputBinding<String> scoringMessage) throws JsonProcessingException {
        final UUID transactionId = payload.transactionId();
        final String inputChunksFilename = saveInputChunksToTheBlobContainer(transactionId, chunkedEntries);

        answerGenerationTableService.upsertIntoTable(
                transactionId.toString(),
                payload.userQuery(),
                payload.queryPrompt(),
                inputChunksFilename,
                llmResponse.formattedLlmResponse(),
                llmResponse.status(),
                reasonColumn(llmResponse),
                OffsetDateTime.now(),
                durationMs
        );

        if (llmResponse.status() == ANSWER_GENERATION_FAILED) {
            LOGGER.warn("Skipping scoring for failed generation of transactionId={} (reason: {})",
                    transactionId, llmResponse.reason());
            return;
        }

        final String filename = saveLlmResponseToTheBlobContainer(transactionId, payload.userQuery(), payload.queryPrompt(),
                llmResponse.formattedLlmResponse(), chunkedEntries);
        scoringMessage.setValue(getObjectMapper().writeValueAsString(new ScoringQueuePayload(filename)));

        LOGGER.info("Answer generation completed for transactionId={} in {} ms", transactionId, durationMs);
    }

    /**
     * Applies the citation-guard exhaustion policy once queue redelivery is used up:
     * DELIVER (default) persists and scores the degraded answer carried by the exception, with
     * the guard reason in the table's reason column; REJECT records a FAILED row with the
     * reason and skips blob + scoring.
     */
    private void handleCitationExhaustion(final AnswerGenerationQueuePayload payload,
                                          final List<ChunkedEntry> chunkedEntries,
                                          final CitationDegradedException e,
                                          final long durationMs,
                                          final OutputBinding<String> scoringMessage) {
        if (isNull(payload) || isNull(payload.transactionId())) {
            LOGGER.error("Citation guard: cannot apply exhaustion policy without a payload.", e);
            return;
        }
        final UUID transactionId = payload.transactionId();
        if (guardMode == CitationGuardMode.REJECT) {
            LOGGER.error("Citation guard: rejecting uncited answer for transactionId={} — {}", transactionId, e.getMessage());
            recordAnswerGenerationFailed(payload, e.getMessage(), durationMs);
            return;
        }
        LOGGER.warn("Citation guard: delivering citation-degraded answer for transactionId={} — {}", transactionId, e.getMessage());
        try {
            final LlmResponse degraded = new LlmResponse(
                    e.rawLlmResponse(), e.formattedText(), ANSWER_GENERATED, e.getMessage());
            persistAnswer(payload, degraded, chunkedEntries, durationMs, scoringMessage);
        } catch (final Exception persistFailure) {
            LOGGER.error("Citation guard: failed to persist delivered degraded answer for transactionId={}", transactionId, persistFailure);
            recordAnswerGenerationFailed(payload, persistFailure.getMessage(), durationMs);
        }
    }

    /** Reason column value: the reason when one was recorded, the generic message for reasonless failures, else null. */
    private String reasonColumn(final LlmResponse llmResponse) {
        if (llmResponse.reason() != null) {
            return llmResponse.reason();
        }
        return llmResponse.status() == ANSWER_GENERATION_FAILED ? "Error generating response" : null;
    }

    /** The rethrow that forces queue redelivery; message shape is asserted by tests and log queries. */
    private RuntimeException redeliveryException(final AnswerGenerationQueuePayload payload,
                                                 final String qualifier, final Exception cause) {
        final UUID trnId = nonNull(payload) ? payload.transactionId() : null;
        return new RuntimeException(format("Retrying AnswerGeneration for transactionId='%s'%s", trnId, qualifier), cause);
    }

    private void recordAnswerGenerationFailed(final AnswerGenerationQueuePayload payload, final String errorMessage, final long durationMs) {
        answerGenerationTableService.upsertIntoTable(
                payload.transactionId().toString(),
                payload.userQuery(),
                payload.queryPrompt(),
                null,
                null,
                ANSWER_GENERATION_FAILED,
                errorMessage,
                OffsetDateTime.now(),
                durationMs
        );
    }

    private String saveLlmResponseToTheBlobContainer(final UUID transactionId, final String userQuery, final String queryPrompt,
                                                     final String llmResponse, final List<ChunkedEntry> chunkedEntries) throws JsonProcessingException {
        final String filename = getAnswerWithChunksFilename(transactionId);
        final ScoringPayload scoringPayload = new ScoringPayload(userQuery,
                llmResponse, queryPrompt, chunkedEntries, transactionId.toString());
        blobPersistenceEvalPayloadsService.saveBlob(filename, convert(scoringPayload));
        return filename;
    }

    private String saveInputChunksToTheBlobContainer(final UUID transactionId, final List<ChunkedEntry> chunkedEntries) throws JsonProcessingException {
        final String inputChunksFilename = getInputChunksFilename(transactionId);
        final InputChunksPayload inputChunksPayload = new InputChunksPayload(chunkedEntries);

        blobPersistenceInputChunksService.saveBlob(inputChunksFilename, convert(inputChunksPayload));
        return inputChunksFilename;
    }
}
