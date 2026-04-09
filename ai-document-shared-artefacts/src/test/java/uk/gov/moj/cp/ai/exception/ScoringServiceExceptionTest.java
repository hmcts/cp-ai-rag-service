package uk.gov.moj.cp.ai.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoringServiceExceptionTest {

    @Test
    void shouldCreateException() {
        Exception cause = new RuntimeException("cause");
        ScoringServiceException ex = new ScoringServiceException("msg", cause);

        assertEquals("msg", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}