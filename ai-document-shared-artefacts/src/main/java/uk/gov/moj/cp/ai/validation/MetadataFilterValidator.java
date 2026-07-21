package uk.gov.moj.cp.ai.validation;

import static uk.gov.moj.cp.ai.index.IndexConstants.CLIENT_ID;
import static uk.gov.moj.cp.ai.index.IndexConstants.IS_ACTIVE;

import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;
import java.util.Set;

/**
 * Rejects caller-supplied metadata filters that reference internal, reserved keys. The reserved
 * keys ({@code clientId}, {@code is_active}) are used by internal scoping/security-trimming clauses
 * and must never be settable through a caller's metadata filter. The rejection is unconditional and
 * does not consult any enforcement flag.
 */
public final class MetadataFilterValidator {

    /**
     * Keys a caller is not permitted to supply in a metadata filter. Matched case-insensitively.
     */
    public static final Set<String> RESERVED_KEYS = Set.of(CLIENT_ID, IS_ACTIVE);

    private MetadataFilterValidator() {
    }

    /**
     * Validates that none of the supplied metadata filters use a reserved key.
     *
     * @param metadataFilters the caller-supplied metadata filters (may be null or empty)
     * @return a list of validation error messages; empty when the filters are acceptable
     */
    public static List<String> validateReservedKeys(final List<KeyValuePair> metadataFilters) {
        // Scaffold: reserved-key detection is not yet implemented; pass-through until built.
        return List.of();
    }
}
