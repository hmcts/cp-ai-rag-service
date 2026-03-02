package uk.gov.moj.cp.metadata.check.utils;

import uk.gov.hmcts.cp.openapi.model.MetadataFilter;
import uk.gov.moj.cp.ai.util.ObjectMapperFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

public class MetadataFilterTransformer {

    public static Map<String, String> listToMap(final List<MetadataFilter> metadataFilters) {
        return metadataFilters.stream()
                .collect(Collectors.toMap(
                        MetadataFilter::getKey,
                        MetadataFilter::getValue,
                        (oldValue, newValue) -> newValue
                ));
    }

    public static Map<String, String> stringToMap(final String metadataMapAsString) throws JsonProcessingException {
        return ObjectMapperFactory.getObjectMapper().readValue(metadataMapAsString, new TypeReference<Map<String, String>>() {
        });
    }
}
