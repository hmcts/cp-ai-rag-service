package uk.gov.moj.cp.ai.model;

import java.math.BigDecimal;

public record ChunkedEntry(
        String documentFileName,
        String chunk,
        Integer pageNumber,
        BigDecimal score


) {
}
