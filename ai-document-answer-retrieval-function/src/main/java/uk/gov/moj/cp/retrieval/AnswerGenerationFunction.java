package uk.gov.moj.cp.retrieval;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.IDEMPOTENCY_LEASE_TTL_SECONDS;
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

import uk.gov.moj.cp.ai.client.identity.ClientId;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.idempotency.IdempotencyGuard;
import uk.gov.moj.cp.ai.idempotency.LeaseConflictException;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.InputChunksPayload;
import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.retrieval.exception.CitationDegradedException;
import uk.gov.moj.cp.retrieval.exception.RedeliveryException;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.CitationGuardMode;
import uk.gov.moj.cp.retrieval.model.LlmResponse;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.Duration;
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
    private final IdempotencyGuard idempotencyGuard;

    public AnswerGenerationFunction() {
        this.embedDataService = new EmbedDataService();
        this.searchService = new AzureAISearchService();
        this.responseGenerationService = new ResponseGenerationService();

        this.blobPersistenceEvalPayloadsService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_EVAL_PAYLOADS));
        this.blobPersistenceInputChunksService = new BlobPersistenceService(getRequiredEnv(STORAGE_ACCOUNT_BLOB_CONTAINER_NAME_INPUT_CHUNKS));
        this.answerGenerationTableService = new AnswerGenerationTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_ANSWER_GENERATION));
        this.guardMode = CitationGuardMode.fromEnv();
        this.idempotencyGuard = buildIdempotencyGuard(this.answerGenerationTableService);
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
        this.idempotencyGuard = buildIdempotencyGuard(answerGenerationTableService);
    }

    private static IdempotencyGuard buildIdempotencyGuard(final AnswerGenerationTableService store) {
        // Default TTL must stay BELOW visibilityTimeout × (maxDequeueCount − 1) — the total
        // redelivery span — or a crashed leaseholder's lease outlives the retry budget and the
        // row is stuck non-terminal forever. 300s also comfortably exceeds a healthy attempt.
        return new IdempotencyGuard(store,
                Duration.ofSeconds(getRequiredEnvAsInteger(IDEMPOTENCY_LEASE_TTL_SECONDS, "300")));
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
        String clientId = null;

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
            final AnswerGenerationQueuePayload validPayload = payload;
            // A payload-carried clientId is re-validated defensively before use; a legacy message
            // without one keeps the null-scoped (legacy) claim, search and writes.
            clientId = validatedClientId(validPayload.clientId());
            final String claimClientId = clientId;

            LOGGER.info("Starting answer generation for transactionId '{}'", transactionId);

            idempotencyGuard.runOnce(claimClientId, transactionId.toString(), token ->
                    processWithClaim(validPayload, token, scoringMessage, dequeueCount, maxDequeueCount, startTime));

        } catch (RedeliveryException e) {
            // Redelivery decision already made inside processWithClaim (lease released by the guard).
            throw e;
        } catch (EtagMismatchException e) {
            // Lost the fencing race at completion: another worker reclaimed the expired lease and
            // owns the outcome. Discard this result; no scoring, no rethrow.
            LOGGER.warn("Fenced write rejected for transactionId='{}' — another worker owns the outcome; discarding this attempt's result",
                    transactionIdOf(payload), e);
        } catch (LeaseConflictException e) {
            rethrowOrWarnOnLiveLease(payload, e, dequeueCount, maxDequeueCount);
        } catch (Exception e) {
            handleClaimFailure(payload, clientId, e, dequeueCount, maxDequeueCount, startTime);
        }
    }

    /** Legacy null clientId stays null; a present one is re-validated as a UUID before use. */
    private static String validatedClientId(final String clientId) {
        return isNullOrEmpty(clientId) ? null : ClientId.requireValid(clientId);
    }

    /**
     * Another worker holds a live lease: rethrow to redeliver-and-recheck while budget remains;
     * at exhaustion never overwrite the possibly-completing leaseholder with FAILED — if it
     * crashed, the row stays PENDING, surfaced here for alerting.
     */
    private void rethrowOrWarnOnLiveLease(final AnswerGenerationQueuePayload payload, final LeaseConflictException e,
                                          final long dequeueCount, final int maxDequeueCount) {
        if (dequeueCount < maxDequeueCount) {
            throw redeliveryException(payload, " (lease held)", e);
        }
        LOGGER.warn("Delivery attempts exhausted while a live lease exists for transactionId='{}' — leaving the outcome to the leaseholder",
                transactionIdOf(payload), e);
    }

    /** Failures before or during the claim (message parsing, status-row reads). */
    private void handleClaimFailure(final AnswerGenerationQueuePayload payload, final String clientId, final Exception e,
                                    final long dequeueCount, final int maxDequeueCount, final long startTime) {
        if (dequeueCount < maxDequeueCount) {
            throw redeliveryException(payload, "", e);
        }
        LOGGER.error("Answer generation failed", e);
        if (nonNull(payload) && nonNull(payload.transactionId())) {
            recordAnswerGenerationFailedIfSafe(payload, clientId, e.getMessage(), currentTimeMillis() - startTime);
        }
    }

    private static UUID transactionIdOf(final AnswerGenerationQueuePayload payload) {
        return nonNull(payload) ? payload.transactionId() : null;
    }

    /**
     * The claimed section: the expensive pipeline plus the retry/exhaustion policy, all fenced by
     * the claim token. Only two exception types escape: {@link RedeliveryException} (budget
     * remains — the guard releases the lease so the next delivery re-claims immediately) and
     * {@link EtagMismatchException} (fence lost — never converted into a FAILED write).
     */
    private void processWithClaim(final AnswerGenerationQueuePayload payload,
                                  final ClaimToken token,
                                  final OutputBinding<String> scoringMessage,
                                  final long dequeueCount,
                                  final int maxDequeueCount,
                                  final long startTime) {
        List<ChunkedEntry> chunkedEntries = null;
        try {
            final List<Float> embeddings = embedDataService.getEmbedding(payload.userQuery());
            chunkedEntries = searchService.search(token.clientId(), payload.userQuery(), embeddings, payload.metadataFilter());
            final LlmResponse llmResponse = responseGenerationService.generateResponse(payload.userQuery(), chunkedEntries, payload.queryPrompt());

            persistAnswer(payload, llmResponse, chunkedEntries, currentTimeMillis() - startTime, scoringMessage, token);
        } catch (EtagMismatchException e) {
            throw e;
        } catch (CitationDegradedException e) {
            // Citation-guard retry rides the queue redelivery mechanism: each redelivery is a
            // fresh, short invocation (re-embed, re-search, one LLM call) rather than a
            // long-running in-process loop.
            if (dequeueCount < maxDequeueCount) {
                throw redeliveryException(payload, " (citation-degraded)", e);
            }
            handleCitationExhaustion(payload, chunkedEntries, e, currentTimeMillis() - startTime, scoringMessage, token);
        } catch (Exception e) {
            if (dequeueCount >= maxDequeueCount) {
                LOGGER.error("Answer generation failed", e);
                recordAnswerGenerationFailed(payload, e.getMessage(), currentTimeMillis() - startTime, token);
            } else {
                throw redeliveryException(payload, "", e);
            }
        }
    }

    /**
     * Persists one generated answer: input-chunks blob, then — unless the generation FAILED, in
     * which case only the fenced status row is written (nothing meaningful to score) — the eval
     * blob and serialized scoring message, the fenced status row (with the reason column when
     * there is one), and finally the scoring enqueue. Every fallible step precedes the fenced
     * terminal write; scoring follows it. Shared by the happy path and the citation-guard
     * DELIVER branch.
     */
    private void persistAnswer(final AnswerGenerationQueuePayload payload,
                               final LlmResponse llmResponse,
                               final List<ChunkedEntry> chunkedEntries,
                               final long durationMs,
                               final OutputBinding<String> scoringMessage,
                               final ClaimToken token) throws JsonProcessingException {
        final UUID transactionId = payload.transactionId();
        final String clientId = token.clientId();
        final String inputChunksFilename = saveInputChunksToTheBlobContainer(clientId, transactionId, chunkedEntries);

        if (llmResponse.status() == ANSWER_GENERATION_FAILED) {
            upsertTerminalFenced(payload, llmResponse, inputChunksFilename, durationMs, token);
            LOGGER.warn("Skipping scoring for failed generation of transactionId={} (reason: {})",
                    transactionId, llmResponse.reason());
            return;
        }

        // Everything fallible happens BEFORE the fenced terminal write: once the row flips to a
        // terminal status a redelivery is skipped as already-done, so a tail step failing after
        // it could never be retried. Blob writes before a lost fence are benign overwrites.
        final String filename = saveLlmResponseToTheBlobContainer(clientId, transactionId, payload.userQuery(), payload.queryPrompt(),
                llmResponse.formattedLlmResponse(), chunkedEntries);
        final String scoringMessageBody = getObjectMapper().writeValueAsString(new ScoringQueuePayload(filename));

        // Fenced on the claim-time ETag: a worker whose lease was reclaimed gets a 412 here,
        // which aborts before the scoring enqueue.
        upsertTerminalFenced(payload, llmResponse, inputChunksFilename, durationMs, token);

        scoringMessage.setValue(scoringMessageBody);

        LOGGER.info("Answer generation completed for transactionId={} in {} ms", transactionId, durationMs);
    }

    private void upsertTerminalFenced(final AnswerGenerationQueuePayload payload, final LlmResponse llmResponse,
                                      final String inputChunksFilename, final long durationMs, final ClaimToken token) {
        answerGenerationTableService.upsertTerminalFenced(
                token.clientId(),
                payload.transactionId().toString(),
                payload.userQuery(),
                payload.queryPrompt(),
                inputChunksFilename,
                llmResponse.formattedLlmResponse(),
                llmResponse.status(),
                reasonColumn(llmResponse),
                OffsetDateTime.now(),
                durationMs,
                token.etag()
        );
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
                                          final OutputBinding<String> scoringMessage,
                                          final ClaimToken token) {
        if (isNull(payload) || isNull(payload.transactionId())) {
            LOGGER.error("Citation guard: cannot apply exhaustion policy without a payload.", e);
            return;
        }
        final UUID transactionId = payload.transactionId();
        if (guardMode == CitationGuardMode.REJECT) {
            LOGGER.error("Citation guard: rejecting uncited answer for transactionId={} — {}", transactionId, e.getMessage());
            recordAnswerGenerationFailed(payload, e.getMessage(), durationMs, token);
            return;
        }
        LOGGER.warn("Citation guard: delivering citation-degraded answer for transactionId={} — {}", transactionId, e.getMessage());
        try {
            final LlmResponse degraded = new LlmResponse(
                    e.rawLlmResponse(), e.formattedText(), ANSWER_GENERATED, e.getMessage());
            persistAnswer(payload, degraded, chunkedEntries, durationMs, scoringMessage, token);
        } catch (final EtagMismatchException fenceLoss) {
            // Never convert a fence loss into a FAILED write — the reclaimer owns the outcome.
            throw fenceLoss;
        } catch (final Exception persistFailure) {
            LOGGER.error("Citation guard: failed to persist delivered degraded answer for transactionId={}", transactionId, persistFailure);
            recordAnswerGenerationFailed(payload, persistFailure.getMessage(), durationMs, token);
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
    private RedeliveryException redeliveryException(final AnswerGenerationQueuePayload payload,
                                                    final String qualifier, final Exception cause) {
        return new RedeliveryException(format("Retrying AnswerGeneration for transactionId='%s'%s", transactionIdOf(payload), qualifier), cause);
    }

    /** Fenced FAILED write for exhaustion paths that hold a claim. */
    private void recordAnswerGenerationFailed(final AnswerGenerationQueuePayload payload, final String errorMessage,
                                              final long durationMs, final ClaimToken token) {
        answerGenerationTableService.upsertTerminalFenced(
                token.clientId(),
                payload.transactionId().toString(),
                payload.userQuery(),
                payload.queryPrompt(),
                null,
                null,
                ANSWER_GENERATION_FAILED,
                errorMessage,
                OffsetDateTime.now(),
                durationMs,
                token.etag()
        );
    }

    /**
     * FAILED write for failures where no claim was ever obtained (status-row read errors at
     * exhaustion). Re-checks the row first and writes fenced on the freshly read ETag — it must
     * never overwrite a terminal outcome or a live leaseholder's in-progress work (the row may
     * belong to a completed or still-running duplicate). If even the re-check fails, nothing is
     * written and the row is left for the leaseholder / alerting.
     */
    private void recordAnswerGenerationFailedIfSafe(final AnswerGenerationQueuePayload payload, final String clientId, final String errorMessage, final long durationMs) {
        final String transactionId = payload.transactionId().toString();
        try {
            final var snapshot = answerGenerationTableService.readForClaim(clientId, transactionId);
            if (snapshot == null) {
                answerGenerationTableService.upsertIntoTable(
                        clientId, transactionId, payload.userQuery(), payload.queryPrompt(),
                        null, null, ANSWER_GENERATION_FAILED, errorMessage, OffsetDateTime.now(), durationMs);
                return;
            }
            if (answerGenerationTableService.isTerminal(snapshot.status())) {
                LOGGER.info("Not recording FAILED for transactionId={} — row is already terminal ({})", transactionId, snapshot.status());
                return;
            }
            if (snapshot.leaseExpiresAt() != null && snapshot.leaseExpiresAt().isAfter(OffsetDateTime.now())) {
                LOGGER.warn("Not recording FAILED for transactionId={} — another worker holds a live lease", transactionId);
                return;
            }
            answerGenerationTableService.upsertTerminalFenced(
                    clientId, transactionId, payload.userQuery(), payload.queryPrompt(),
                    null, null, ANSWER_GENERATION_FAILED, errorMessage, OffsetDateTime.now(), durationMs,
                    snapshot.etag());
        } catch (EtagMismatchException e) {
            LOGGER.warn("Not recording FAILED for transactionId={} — row changed concurrently; leaving the outcome to its owner", transactionId, e);
        } catch (Exception e) {
            LOGGER.error("Unable to safely record FAILED for transactionId={} — leaving row unchanged", transactionId, e);
        }
    }

    private String saveLlmResponseToTheBlobContainer(final String clientId, final UUID transactionId, final String userQuery, final String queryPrompt,
                                                     final String llmResponse, final List<ChunkedEntry> chunkedEntries) throws JsonProcessingException {
        final String filename = getAnswerWithChunksFilename(clientId, transactionId);
        final ScoringPayload scoringPayload = new ScoringPayload(userQuery,
                llmResponse, queryPrompt, chunkedEntries, transactionId.toString(), clientId);
        blobPersistenceEvalPayloadsService.saveBlob(filename, convert(scoringPayload));
        return filename;
    }

    private String saveInputChunksToTheBlobContainer(final String clientId, final UUID transactionId, final List<ChunkedEntry> chunkedEntries) throws JsonProcessingException {
        final String inputChunksFilename = getInputChunksFilename(clientId, transactionId);
        final InputChunksPayload inputChunksPayload = new InputChunksPayload(chunkedEntries);

        blobPersistenceInputChunksService.saveBlob(inputChunksFilename, convert(inputChunksPayload));
        return inputChunksFilename;
    }
}
