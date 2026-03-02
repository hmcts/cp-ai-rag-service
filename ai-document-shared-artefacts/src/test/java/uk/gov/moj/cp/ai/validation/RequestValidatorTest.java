package uk.gov.moj.cp.ai.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    // A simple POJO to test validation logic
    private static class TestRequest {
        @NotBlank(message = "Name cannot be blank")
        private String name;

        @NotNull(message = "ID cannot be null")
        private Integer id;

        public TestRequest(String name, Integer id) {
            this.name = name;
            this.id = id;
        }
    }

    @Test
    void shouldReturnNoErrors_WhenObjectIsValid() {
        TestRequest validRequest = new TestRequest("Valid Name", 123);

        List<String> errors = RequestValidator.validate(validRequest);

        assertTrue(errors.isEmpty(), "Should have no validation errors");
    }

    @Test
    void shouldReturnErrors_WhenObjectIsInvalid() {
        // One field blank, one field null
        TestRequest invalidRequest = new TestRequest("", null);

        List<String> errors = RequestValidator.validate(invalidRequest);

        assertFalse(errors.isEmpty(), "Should have validation errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("name: Name cannot be blank")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("id: ID cannot be null")));
    }
}