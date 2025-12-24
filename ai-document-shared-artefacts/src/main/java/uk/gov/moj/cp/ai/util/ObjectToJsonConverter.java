package uk.gov.moj.cp.ai.util;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ObjectToJsonConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectToJsonConverter.class);

    public static String convert(final Object object) {
        try {
            return getObjectMapper().writeValueAsString(object);
        } catch (Exception e) {
            LOGGER.error("Error converting object to JSON", e);
            return "{}";
        }
    }
}
