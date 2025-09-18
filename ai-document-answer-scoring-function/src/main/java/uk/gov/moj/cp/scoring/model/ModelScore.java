package uk.gov.moj.cp.scoring.model;

import java.math.BigDecimal;

public record ModelScore(
        BigDecimal groundednessScore,
        String reasoning
) {}
