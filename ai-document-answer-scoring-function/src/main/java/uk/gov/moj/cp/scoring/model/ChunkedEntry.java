package uk.gov.moj.cp.scoring.model;

import java.math.BigDecimal;

public record ChunkedEntry(
        String documentFileName,
        String chunk,
        Integer pageNumber,
        BigDecimal score


) {
}
