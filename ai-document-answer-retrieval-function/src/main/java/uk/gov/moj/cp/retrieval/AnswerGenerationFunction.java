package uk.gov.moj.cp.retrieval;

import static java.util.UUID.randomUUID;
import static uk.gov.moj.cp.ai.SharedSystemVariables.AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING;
import static uk.gov.moj.cp.ai.SharedSystemVariables.STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.AnswerGenerationStatus;
import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.QueryResponse;
import uk.gov.moj.cp.ai.model.ScoringQueuePayload;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.util.List;
import java.util.UUID;

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

    private final EmbedDataService embedDataService;
    private final AzureAISearchService searchService;
    private final ResponseGenerationService responseGenerationService;
    private final BlobPersistenceService blobPersistenceService;
    private final AnswerGenerationTableStorageService tableStorageService;

    public AnswerGenerationFunction() {
        this.embedDataService = new EmbedDataService();
        this.searchService = new AzureAISearchService();
        this.responseGenerationService = new ResponseGenerationService();
        this.blobPersistenceService = new BlobPersistenceService();
        this.tableStorageService =
                new AnswerGenerationTableStorageService("AnswerGeneration");
    }

    @FunctionName("AnswerGeneration")
    public void run(
            @QueueTrigger(
                    name = "queueMessage",
                    queueName = "%ANSWER_GENERATION_QUEUE%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) final String queueMessage,

            @QueueOutput(
                    name = "scoringMessage",
                    queueName = "%" + STORAGE_ACCOUNT_QUEUE_ANSWER_SCORING + "%",
                    connection = AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING
            ) final OutputBinding<String> scoringMessage
    ) {

        long startTime = System.currentTimeMillis();
        AnswerGenerationQueuePayload payload = null;

        try {
            if (isNullOrEmpty(queueMessage)) {
                throw new IllegalArgumentException("Queue message is empty");
            }

            payload = getObjectMapper()
                    .readValue(queueMessage, AnswerGenerationQueuePayload.class);

            // Validation
            if (payload.transactionId() == null
                    || isNullOrEmpty(payload.userQuery())
                    || isNullOrEmpty(payload.queryPrompt())
                    || payload.metadataFilter() == null
                    || payload.metadataFilter().isEmpty()) {

                throw new IllegalArgumentException(
                        "transactionId, userQuery, queryPrompt and metadataFilter are required");
            }

            final UUID transactionId = payload.transactionId();

            LOGGER.info(
                    "Starting answer generation for transactionId={}",
                    transactionId);

            final List<Float> embeddings =
                    embedDataService.getEmbedding(payload.userQuery());

            final List<ChunkedEntry> chunkedEntries =
                    searchService.search(
                            payload.userQuery(),
                            embeddings,
                            payload.metadataFilter()
                    );

            final String llmResponse =
                    responseGenerationService.generateResponse(
                            payload.userQuery(),
                            chunkedEntries,
                            payload.queryPrompt()
                    );

            final long durationMs =
                    System.currentTimeMillis() - startTime;

            tableStorageService.upsertIntoTable(
                    transactionId.toString(),
                    payload.userQuery(),
                    payload.queryPrompt(),
                    getObjectMapper().writeValueAsString(chunkedEntries),
                    llmResponse,
                    AnswerGenerationStatus.ANSWER_GENERATED,
                    null,
                    durationMs
            );

           // Persist blob + scoring queue
            final String filename =
                    "llm-answer-with-chunks-" + randomUUID() + ".json";

            blobPersistenceService.saveBlob(
                    filename,
                    getObjectMapper().writeValueAsString(
                            new QueryResponse(
                                    payload.userQuery(),
                                    llmResponse,
                                    payload.queryPrompt(),
                                    chunkedEntries
                            )
                    )
            );

            scoringMessage.setValue(
                    getObjectMapper().writeValueAsString(
                            new ScoringQueuePayload(filename)));

            LOGGER.info(
                    "Answer generation completed for transactionId={} in {} ms",
                    transactionId, durationMs);

        } catch (Exception e) {

            final long durationMs =
                    System.currentTimeMillis() - startTime;

            LOGGER.error("Answer generation failed", e);

            if (payload != null && payload.transactionId() != null) {
                tableStorageService.upsertIntoTable(
                        payload.transactionId().toString(),
                        payload.userQuery(),
                        payload.queryPrompt(),
                        null,
                        null,
                        AnswerGenerationStatus.ANSWER_GENERATION_FAILED,
                        e.getMessage(),
                        durationMs
                );
            }

            throw new RuntimeException("Answer generation failed", e);
        }
    }
}
