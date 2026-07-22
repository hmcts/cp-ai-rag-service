package uk.gov.moj.cp.ai.util;

import static java.util.UUID.fromString;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UuidUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(UuidUtil.class);

    public static boolean isValid(final String uuidString) {
        if (isNullOrEmpty(uuidString)) {
            return false;
        }
        try {
            fromString(uuidString);
            return true;
        } catch (IllegalArgumentException iae) {
            LOGGER.error("Invalid UUID string: {}", uuidString);
            return false;
        }
    }

    /**
     * Boundary-validation variant that reports validity without logging at ERROR. A caller
     * supplying a malformed identity value at the request edge is an expected, client-driven
     * rejection (mapped to 401), not a server-side fault, so it must not pollute error logs.
     *
     * @return {@code true} when {@code uuidString} is a well-formed UUID, {@code false} otherwise
     *         (including null/blank) — with no ERROR-level log emitted for the invalid case.
     */
    public static boolean isValidQuietly(final String uuidString) {
        if (isNullOrEmpty(uuidString)) {
            return false;
        }
        try {
            fromString(uuidString);
            return true;
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }
}
