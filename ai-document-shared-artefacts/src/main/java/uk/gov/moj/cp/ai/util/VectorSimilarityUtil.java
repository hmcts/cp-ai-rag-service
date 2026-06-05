package uk.gov.moj.cp.ai.util;

import java.util.List;

public class VectorSimilarityUtil {

    private VectorSimilarityUtil() {
        // Utility class
    }

    /**
     * Cosine similarity between two embedding vectors.
     * <p>
     * See https://help.openai.com/en/articles/6824809-embeddings-faq
     * <p>
     * OpenAI embeddings are normalized to length 1; because the vectors are unit-length, cosine
     * similarity can be computed slightly faster as just the dot product.
     *
     * @param vecA first vector
     * @param vecB second vector
     * @return the dot product (== cosine similarity for unit vectors), or 0.0 when either vector is
     *     null or the lengths differ
     */
    public static double cosineSimilarity(final List<Float> vecA, final List<Float> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        for (int i = 0; i < vecA.size(); i++) {
            dotProduct += vecA.get(i) * vecB.get(i);
        }

        return dotProduct;
    }
}
