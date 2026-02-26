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
}
