package uk.gov.moj.cp.metadata.check.utils;

import uk.gov.hmcts.cp.openapi.model.MetadataFilter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MetadataFilterTransformer {

    public static Map<String, String> toMap(final List<MetadataFilter> metadataFilters) {
        return metadataFilters.stream()
                .collect(Collectors.toMap(
                        MetadataFilter::getKey,
                        MetadataFilter::getValue,
                        (oldValue, newValue) -> newValue
                ));
    }
}
