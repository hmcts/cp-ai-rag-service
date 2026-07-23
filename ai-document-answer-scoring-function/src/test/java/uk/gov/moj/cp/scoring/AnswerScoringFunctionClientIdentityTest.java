package uk.gov.moj.cp.scoring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.model.ScoringPayload;
import uk.gov.moj.cp.ai.service.table.AnswerGenerationTableService;
import uk.gov.moj.cp.scoring.model.ModelScore;
import uk.gov.moj.cp.scoring.service.BlobService;
import uk.gov.moj.cp.scoring.service.PublishScoreService;
import uk.gov.moj.cp.scoring.service.ScoringService;

import java.math.BigDecimal;
import java.util.List;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforcement wiring for the scorer: the clientId carried on the scoring payload blob is used when
 * recording the groundedness score back to the table and when publishing the telemetry dimension.
 */
class AnswerScoringFunctionClientIdentityTest {

    private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String TRANSACTION_ID = "22222222-2222-2222-2222-222222222222";

    private AnswerScoringFunction function;
    private ScoringService scoringService;
    private PublishScoreService publishScoreService;
    private AnswerGenerationTableService answerGenerationTableService;
    private BlobService blobService;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        scoringService = mock(ScoringService.class);
        publishScoreService = mock(PublishScoreService.class);
        answerGenerationTableService = mock(AnswerGenerationTableService.class);
        blobService = mock(BlobService.class);
        context = mock(ExecutionContext.class);
        function = new AnswerScoringFunction(scoringService, publishScoreService, blobService, answerGenerationTableService);
    }

    @Test
    @DisplayName("records the groundedness score under the payload client id and publishes it with the client dimension")
    void shouldThreadClientId_intoScoreRecordAndTelemetry() throws Exception {
        final String queueMessage = "{\"filename\":\"scoring.json\"}";
        final ScoringPayload payload = new ScoringPayload("query", "response", "prompt", List.of(), TRANSACTION_ID, CLIENT_ID);
        final BigDecimal score = BigDecimal.valueOf(5);
        when(blobService.readBlob("scoring.json", ScoringPayload.class)).thenReturn(payload);
        when(scoringService.evaluateGroundedness(payload.llmResponse(), payload.userQuery(), payload.queryPrompt(), List.of()))
                .thenReturn(new ModelScore(score, "Well supported"));

        function.run(queueMessage, context);

        verify(publishScoreService).publishGroundednessScore(score, payload.userQuery(), CLIENT_ID);
        verify(answerGenerationTableService).recordGroundednessScore(CLIENT_ID, TRANSACTION_ID, score);
    }

    @Test
    @DisplayName("legacy payload without a client id records the score under the null-scoped partition")
    void shouldRecordUnderNullScope_whenPayloadHasNoClientId() throws Exception {
        final String queueMessage = "{\"filename\":\"scoring.json\"}";
        final ScoringPayload payload = new ScoringPayload("query", "response", "prompt", List.of(), TRANSACTION_ID);
        final BigDecimal score = BigDecimal.valueOf(5);
        when(blobService.readBlob("scoring.json", ScoringPayload.class)).thenReturn(payload);
        when(scoringService.evaluateGroundedness(payload.llmResponse(), payload.userQuery(), payload.queryPrompt(), List.of()))
                .thenReturn(new ModelScore(score, "Well supported"));

        function.run(queueMessage, context);

        verify(answerGenerationTableService).recordGroundednessScore(null, TRANSACTION_ID, score);
    }
}
