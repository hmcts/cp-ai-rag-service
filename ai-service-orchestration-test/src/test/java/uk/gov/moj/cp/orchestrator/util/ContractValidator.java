package uk.gov.moj.cp.orchestrator.util;

import static jakarta.validation.Validation.byDefaultProvider;
import static java.util.stream.Collectors.joining;
import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Asserts that an HTTP response body matches its OpenAPI contract model: strict deserialization
 * (unknown fields fail, catching undocumented additions) followed by jakarta bean validation of
 * the generated model's constraints (required fields, uuid patterns, length limits).
 */
public final class ContractValidator {

    private static final ObjectMapper STRICT_MAPPER = getObjectMapper().copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Validator VALIDATOR = byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    private ContractValidator() {
    }

    public static <T> T assertMatchesContract(final Response response, final Class<T> modelClass) {
        final String body = response.getBody().asString();

        final T model;
        try {
            model = STRICT_MAPPER.readValue(body, modelClass);
        } catch (Exception e) {
            throw new AssertionError("Response body does not deserialize into contract model "
                    + modelClass.getSimpleName() + ": " + e.getMessage() + "\nBody: " + body, e);
        }

        final Set<ConstraintViolation<T>> violations = VALIDATOR.validate(model);
        if (!violations.isEmpty()) {
            final String details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(joining("; "));
            throw new AssertionError("Response body violates contract constraints of "
                    + modelClass.getSimpleName() + " [" + details + "]\nBody: " + body);
        }
        return model;
    }
}
