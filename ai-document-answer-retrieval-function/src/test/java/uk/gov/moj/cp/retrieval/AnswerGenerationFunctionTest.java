package uk.gov.moj.cp.retrieval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.org.webcompere.modelassert.json.JsonAssertions.json;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationQueuePayload;
import uk.gov.moj.cp.retrieval.model.AnswerGenerationStatus;
import uk.gov.moj.cp.retrieval.service.AnswerGenerationTableStorageService;
import uk.gov.moj.cp.retrieval.service.AzureAISearchService;
import uk.gov.moj.cp.retrieval.service.BlobPersistenceService;
import uk.gov.moj.cp.retrieval.service.EmbedDataService;
import uk.gov.moj.cp.retrieval.service.ResponseGenerationService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link AnswerGenerationFunction}
 */
class AnswerGenerationFunctionTest {

    @Mock
    private EmbedDataService mockEmbedDataService;

    @Mock
    private AzureAISearchService mockSearchService;

    @Mock
    private ResponseGenerationService mockResponseGenerationService;

    @Mock
    private BlobPersistenceService mockBlobPersistenceService;

    @Mock
    private AnswerGenerationTableStorageService mockTableStorageService;

    @Mock
    private OutputBinding<String> mockScoringOutputBinding;

    private AnswerGenerationFunction function;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        function = new AnswerGenerationFunction(
                mockEmbedDataService,
                mockSearchService,
                mockResponseGenerationService,
                mockBlobPersistenceService,
                mockTableStorageService
        );
    }

    @Test
    void run_DoesNothing_WhenQueueMessageIsEmpty() {
        function.run("", mockScoringOutputBinding);

        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockTableStorageService, never()).upsertIntoTable(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void run_DoesNothing_WhenPayloadIsInvalid() throws Exception {
        AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        null,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        String queueMessage = objectMapper.writeValueAsString(payload);

        function.run(queueMessage, mockScoringOutputBinding);

        verify(mockEmbedDataService, never()).getEmbedding(anyString());
        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockTableStorageService, never()).upsertIntoTable(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void run_GeneratesAnswer_WhenValidPayloadProvided() throws Exception {
        UUID transactionId = UUID.randomUUID();

        AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        String queueMessage = objectMapper.writeValueAsString(payload);

        List<Float> embeddings = List.of(1.0f, 2.0f);
        List<ChunkedEntry> chunkedEntries =
                List.of(ChunkedEntry.builder()
                        .id("1")
                        .chunk("Sample content")
                        .documentFileName("doc.pdf")
                        .pageNumber(1)
                        .documentId("doc1")
                        .build());

        when(mockEmbedDataService.getEmbedding("query"))
                .thenReturn(embeddings);

        when(mockSearchService.search("query", embeddings, payload.metadataFilter()))
                .thenReturn(chunkedEntries);

        when(mockResponseGenerationService.generateResponse(
                "query", chunkedEntries, "prompt"))
                .thenReturn("generated response");

        function.run(queueMessage, mockScoringOutputBinding);

        verify(mockTableStorageService).upsertIntoTable(
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                anyString(),
                eq("generated response"),
                eq(AnswerGenerationStatus.ANSWER_GENERATED),
                eq(null),
                any(OffsetDateTime.class),
                any(Long.class)
        );

        verify(mockBlobPersistenceService).saveBlob(
                anyString(),
                argThat(
                        json().at("/userQuery").isText("query")
                                .at("/llmResponse").isText("generated response")
                                .at("/queryPrompt").isText("prompt")
                                .at("/chunkedEntries").isArray()
                                .toArgumentMatcher()
                )
        );

        verify(mockScoringOutputBinding).setValue(
                argThat(
                        json().at("/filename")
                                .matches("^llm-answer-with-chunks-.*\\.json$")
                                .toArgumentMatcher()
                )
        );
    }

    @Test
    void run_UpdatesTableWithFailure_WhenExceptionOccurs() throws Exception {
        UUID transactionId = UUID.randomUUID();

        AnswerGenerationQueuePayload payload =
                new AnswerGenerationQueuePayload(
                        transactionId,
                        "query",
                        "prompt",
                        List.of(new KeyValuePair("key", "value"))
                );

        String queueMessage = objectMapper.writeValueAsString(payload);

        when(mockEmbedDataService.getEmbedding("query"))
                .thenThrow(new RuntimeException("Embedding failure"));

        function.run(queueMessage, mockScoringOutputBinding);

        verify(mockTableStorageService).upsertIntoTable(
                eq(transactionId.toString()),
                eq("query"),
                eq("prompt"),
                eq(null),
                eq(null),
                eq(AnswerGenerationStatus.ANSWER_GENERATION_FAILED),
                eq("Embedding failure"),
                any(OffsetDateTime.class),
                any(Long.class)
        );

        verify(mockScoringOutputBinding, never()).setValue(anyString());
        verify(mockBlobPersistenceService, never()).saveBlob(anyString(), anyString());
    }
}
