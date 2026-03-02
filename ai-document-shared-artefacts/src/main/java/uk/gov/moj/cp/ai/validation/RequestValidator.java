package uk.gov.moj.cp.ai.validation;

import static jakarta.validation.Validation.byDefaultProvider;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

public class RequestValidator {

    private static final Validator validator = byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    public static <T> List<String> validate(T object) {
        return validator.validate(object)
                .stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
    }
}
