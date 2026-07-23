package uk.gov.moj.cp.ingestion;

import static java.util.Objects.isNull;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.IDEMPOTENCY_LEASE_TTL_SECONDS;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnvAsInteger;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.client.identity.ClientId;
import uk.gov.moj.cp.ai.exception.EtagMismatchException;
import uk.gov.moj.cp.ai.idempotency.ClaimToken;
import uk.gov.moj.cp.ai.idempotency.IdempotencyGuard;
import uk.gov.moj.cp.ai.idempotency.LeaseConflictException;
import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ai.service.table.DocumentIngestionOutcomeTableService;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;
import uk.gov.moj.cp.ingestion.service.DocumentIngestionOrchestrator;

import java.time.Duration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Function for document ingestion processing.
 */
public class DocumentIngestionFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionFunction.class);
    private final DocumentIngestionOrchestrator documentIngestionOrchestrator;
    private final IdempotencyGuard idempotencyGuard;

    public DocumentIngestionFunction() {
        // The outcome table service is shared between the orchestrator (terminal writes) and
        // the idempotency guard (lease claims against the same rows).
        final DocumentIngestionOutcomeTableService outcomeTableService =
                new DocumentIngestionOutcomeTableService(getRequiredEnv(STORAGE_ACCOUNT_TABLE_DOCUMENT_INGESTION_OUTCOME));
        this.documentIngestionOrchestrator = new DocumentIngestionOrchestrator(outcomeTableService);
        // Default TTL must stay BELOW visibilityTimeout × (maxDequeueCount − 1) — the total
        // redelivery span — or a crashed leaseholder's lease outlives the retry budget and the
        // row is stuck non-terminal forever. 300s also comfortably exceeds a healthy attempt.
        this.idempotencyGuard = new IdempotencyGuard(outcomeTableService,
                Duration.ofSeconds(getRequiredEnvAsInteger(IDEMPOTENCY_LEASE_TTL_SECONDS, "300")));
    }

    DocumentIngestionFunction(DocumentIngestionOrchestrator documentIngestionOrchestrator, IdempotencyGuard idempotencyGuard) {
        this.documentIngestionOrchestrator = documentIngestionOrchestrator;
        this.idempotencyGuard = idempotencyGuard;
    }

    @FunctionName("DocumentIngestion")
    public void run(
            @QueueTrigger(
                    name = "queueMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) String queueMessage,
            @BindingName("DequeueCount") long dequeueCount
    ) throws DocumentProcessingException {

        LOGGER.info("Document ingestion function triggered ");
        //defaultValue of maxDequeueCount should match the value in host.json
        final int maxDequeueCount = getRequiredEnvAsInteger("AzureFunctionsJobHost__extensions__queues__maxDequeueCount", "3");

        if (isNullOrEmpty(queueMessage)) {
            LOGGER.error("Invalid queue queueMessage received: {}", queueMessage);
            return;
        }

        final QueueIngestionMetadata queueIngestionMetadata = toQueueIngestionMetadata(queueMessage);
        if (isNull(queueIngestionMetadata)) {
            return;
        }

        final String documentId = queueIngestionMetadata.documentId();
        // Validated once, before anything else: a legacy message without a clientId keeps the
        // null-scoped claim; an invalid value fails the invocation here (redelivery, then poison)
        // so a corrupt message never enters the pipeline.
        final String clientId = ClientId.requireValidOrNull(queueIngestionMetadata.clientId());
        try {
            LOGGER.info("Parsed ingestion metadata - ID: {}, Name: {}, Blob URL: {}",
                    documentId,
                    queueIngestionMetadata.documentName(),
                    queueIngestionMetadata.blobUrl());

            idempotencyGuard.runOnce(clientId, documentId, token ->
                    processUnderClaim(queueIngestionMetadata, token, dequeueCount, maxDequeueCount));

        } catch (EtagMismatchException e) {
            // Lost the fencing race at completion: another worker reclaimed the expired lease
            // and owns the outcome. Discard this attempt; no rethrow.
            LOGGER.warn("Fenced write rejected for documentId='{}' — another worker owns the outcome; discarding this attempt", documentId, e);
        } catch (LeaseConflictException e) {
            rethrowOrWarnOnLiveLease(documentId, e, dequeueCount, maxDequeueCount);
        } catch (DocumentProcessingException e) {
            // Redelivery decision already made inside the work (lease released by the guard).
            throw e;
        } catch (Exception e) {
            handleClaimFailure(queueIngestionMetadata, clientId, e, dequeueCount, maxDequeueCount);
        }
    }

    /**
     * The claimed section. Broad catch on purpose: a claim IS held here, so any failure's
     * terminal write must go through the fenced path, never the unfenced fallback.
     */
    private void processUnderClaim(final QueueIngestionMetadata queueIngestionMetadata, final ClaimToken token,
                                   final long dequeueCount, final int maxDequeueCount) throws DocumentProcessingException {
        try {
            documentIngestionOrchestrator.processQueueMessage(queueIngestionMetadata, token);

        } catch (EtagMismatchException fenceLoss) {
            // Never convert a fence loss into a retry or a FAILED write.
            throw fenceLoss;
        } catch (Exception processingException) {
            if (dequeueCount < maxDequeueCount) {
                // Re-throw to trigger Azure Function retry mechanism
                throw new DocumentProcessingException("Error processing queueMessage", processingException);
            }
            documentIngestionOrchestrator.processQueueMessageFailed(queueIngestionMetadata, token);
        }
    }

    /**
     * Another worker holds a live lease: rethrow to redeliver-and-recheck while budget remains;
     * at exhaustion never overwrite the possibly-completing leaseholder with FAILED — if it
     * crashed, the row stays non-terminal, surfaced here for alerting.
     */
    private void rethrowOrWarnOnLiveLease(final String documentId, final LeaseConflictException e,
                                          final long dequeueCount, final int maxDequeueCount) throws DocumentProcessingException {
        if (dequeueCount < maxDequeueCount) {
            throw new DocumentProcessingException("Lease held by another worker for documentId: " + documentId, e);
        }
        LOGGER.warn("Delivery attempts exhausted while a live lease exists for documentId='{}' — leaving the outcome to the leaseholder", documentId, e);
    }

    /** Failures during the claim itself (status-row reads etc.) — no claim obtained. */
    private void handleClaimFailure(final QueueIngestionMetadata queueIngestionMetadata, final String clientId,
                                    final Exception e, final long dequeueCount, final int maxDequeueCount) throws DocumentProcessingException {
        if (dequeueCount < maxDequeueCount) {
            throw new DocumentProcessingException("Error processing queueMessage", e);
        }
        LOGGER.error("Document ingestion failed during idempotency claim for documentId='{}'", queueIngestionMetadata.documentId(), e);
        documentIngestionOrchestrator.processQueueMessageFailedIfSafe(queueIngestionMetadata, clientId);
    }

    private QueueIngestionMetadata toQueueIngestionMetadata(final String queueMessage) {
        try {
            return getObjectMapper().readValue(queueMessage, QueueIngestionMetadata.class);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize queue message: {}", queueMessage, e);
        }
        return null;
    }
}
