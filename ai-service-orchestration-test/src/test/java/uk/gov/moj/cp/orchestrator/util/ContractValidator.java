package uk.gov.moj.cp.orchestrator.util;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;
import static uk.gov.moj.cp.ai.validation.RequestValidator.validate;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;

/**
 * Asserts that an HTTP response body matches its OpenAPI contract model: strict deserialization
 * (unknown fields fail, catching undocumented additions) followed by jakarta bean validation of
 * the generated model's constraints (required fields, uuid patterns, length limits). Validation
 * is delegated to the production {@code RequestValidator}, so responses are checked with the
 * identical validator configuration the functions use for requests.
 */
public final class ContractValidator {

    private static final ObjectMapper STRICT_MAPPER = getObjectMapper().copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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

        final List<String> violations = validate(model);
        if (!violations.isEmpty()) {
            throw new AssertionError("Response body violates contract constraints of "
                    + modelClass.getSimpleName() + " " + violations + "\nBody: " + body);
        }
        return model;
    }
}
