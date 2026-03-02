package uk.gov.moj.cp.metadata.check.validation;

import static jakarta.validation.Validation.buildDefaultValidatorFactory;

import java.util.List;

import jakarta.validation.Validator;

public class RequestValidator {

    private static final Validator validator = buildDefaultValidatorFactory().getValidator();

    public static <T> List<String> validate(T object) {
        return validator.validate(object)
                .stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
    }
}
