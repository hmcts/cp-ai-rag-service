package uk.gov.moj.cp.ai.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataFilterValidatorTest {

    @Test
    @DisplayName("Rejects a metadata filter using the reserved client id key")
    void shouldRejectReservedClientIdKey() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("clientId", "some-value"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertFalse(errors.isEmpty(), "Reserved key 'clientId' should be rejected");
    }

    @Test
    @DisplayName("Rejects a metadata filter using the reserved is_active key")
    void shouldRejectReservedIsActiveKey() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("is_active", "true"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertFalse(errors.isEmpty(), "Reserved key 'is_active' should be rejected");
    }

    @Test
    @DisplayName("Rejects a reserved key supplied in a different letter case")
    void shouldRejectReservedKeyRegardlessOfCase() {
        final List<KeyValuePair> filters = List.of(new KeyValuePair("ClientId", "some-value"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertFalse(errors.isEmpty(), "Reserved keys should be matched case-insensitively");
    }

    @Test
    @DisplayName("Rejects a reserved key when it appears alongside non-reserved keys")
    void shouldRejectReservedKeyMixedWithNonReservedKeys() {
        final List<KeyValuePair> filters = List.of(
                new KeyValuePair("caseName", "Crown v Smith"),
                new KeyValuePair("is_active", "false"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertFalse(errors.isEmpty(), "A reserved key mixed with acceptable keys should still be rejected");
    }

    @Test
    @DisplayName("Rejects a reserved key with no enforcement flag configured (always-on)")
    void shouldRejectReservedKeyWhenEnforcementDisabled() {
        // No CLIENT_FILTERING_ENABLED is set here (default: disabled). The rejection must still
        // apply, proving it is not gated by the enforcement flag.
        final List<KeyValuePair> filters = List.of(new KeyValuePair("clientId", "some-value"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertFalse(errors.isEmpty(), "Reserved-key rejection must apply regardless of the enforcement flag");
    }

    @Test
    @DisplayName("Accepts metadata filters that use only non-reserved keys")
    void shouldAcceptNonReservedKeys() {
        final List<KeyValuePair> filters = List.of(
                new KeyValuePair("caseName", "Crown v Smith"),
                new KeyValuePair("documentType", "statement"));

        final List<String> errors = MetadataFilterValidator.validateReservedKeys(filters);

        assertTrue(errors.isEmpty(), "Non-reserved keys should be accepted");
    }

    @Test
    @DisplayName("Accepts a null metadata filter list")
    void shouldAcceptNullFilters() {
        final List<String> errors = MetadataFilterValidator.validateReservedKeys(null);

        assertTrue(errors.isEmpty(), "A null filter list should be accepted");
    }
}
