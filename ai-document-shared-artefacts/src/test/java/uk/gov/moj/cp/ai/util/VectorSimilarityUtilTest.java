package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorSimilarityUtilTest {

    private static final double DELTA = 1e-9;

    @Test
    @DisplayName("Returns the dot product for equal-length vectors")
    void returnsDotProductForEqualLengthVectors() {
        final List<Float> a = Arrays.asList(1.0f, 2.0f, 3.0f);
        final List<Float> b = Arrays.asList(4.0f, 5.0f, 6.0f);
        assertEquals(32.0, VectorSimilarityUtil.cosineSimilarity(a, b), DELTA);
    }

    @Test
    @DisplayName("Returns 1.0 for identical unit vectors")
    void returnsOneForIdenticalUnitVectors() {
        final List<Float> v = Arrays.asList(1.0f, 0.0f);
        assertEquals(1.0, VectorSimilarityUtil.cosineSimilarity(v, v), DELTA);
    }

    @Test
    @DisplayName("Returns 0.0 for orthogonal vectors")
    void returnsZeroForOrthogonalVectors() {
        assertEquals(0.0, VectorSimilarityUtil.cosineSimilarity(
                Arrays.asList(1.0f, 0.0f), Arrays.asList(0.0f, 1.0f)), DELTA);
    }

    @Test
    @DisplayName("Returns 0.0 when either vector is null")
    void returnsZeroWhenEitherVectorIsNull() {
        final List<Float> v = Arrays.asList(1.0f, 2.0f);
        assertEquals(0.0, VectorSimilarityUtil.cosineSimilarity(null, v), DELTA);
        assertEquals(0.0, VectorSimilarityUtil.cosineSimilarity(v, null), DELTA);
        assertEquals(0.0, VectorSimilarityUtil.cosineSimilarity(null, null), DELTA);
    }

    @Test
    @DisplayName("Returns 0.0 for mismatched-length vectors")
    void returnsZeroForMismatchedLengthVectors() {
        assertEquals(0.0, VectorSimilarityUtil.cosineSimilarity(
                Arrays.asList(1.0f, 2.0f), Arrays.asList(1.0f)), DELTA);
    }
}
