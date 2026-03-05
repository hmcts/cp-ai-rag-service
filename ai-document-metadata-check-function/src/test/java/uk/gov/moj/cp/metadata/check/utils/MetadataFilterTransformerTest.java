package uk.gov.moj.cp.metadata.check.utils;

import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer.listToMap;
import static uk.gov.moj.cp.metadata.check.utils.MetadataFilterTransformer.stringToMap;

import uk.gov.hmcts.cp.openapi.model.MetadataFilter;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

public class MetadataFilterTransformerTest {

    @Test
    void shouldConvertListListToMap() {
        final List<MetadataFilter> filters = List.of(
                new MetadataFilter("key1", "value1"),
                new MetadataFilter("key2", "value2")
        );

        final Map<String, String> result = listToMap(filters);

        assertThat(2, is(result.size()));
        assertThat("value1", is(result.get("key1")));
        assertThat("value2", is(result.get("key2")));
    }

    @Test
    void shouldKeepLatestValueWhenDuplicateKeysExist() {
        final List<MetadataFilter> filters = List.of(
                new MetadataFilter("key1", "value1"),
                new MetadataFilter("key1", "value2")
        );

        final Map<String, String> result = listToMap(filters);

        assertThat(1, is(result.size()));
        assertThat("value2", is(result.get("key1")));
    }

    @Test
    void shouldReturnEmptyMapWhenListIsEmpty() {
        final List<MetadataFilter> filters = emptyList();

        final Map<String, String> result = listToMap(filters);

        assertThat(result.isEmpty(), is(true));
    }

    @Test
    void shouldThrowNullPointerExceptionWhenInputIsNull() {
        assertThrows(NullPointerException.class, () -> listToMap(null));
    }

    @Test
    void shouldConvertValidJsonToMap() throws Exception {
        String json = "{\"key1\":\"value1\",\"key2\":\"value2\"}";

        Map<String, String> result = stringToMap(json);

        assertThat(result.size(), is(2));
        assertThat(result.get("key1"), is("value1"));
        assertThat(result.get("key2"), is("value2"));
    }

    @Test
    void shouldReturnEmptyMapForEmptyJsonObject() throws Exception {
        String json = "{}";

        Map<String, String> result = stringToMap(json);

        assertThat(result.isEmpty(), is(true));
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String invalidJson = "{key1:value1}";

        assertThrows(JsonProcessingException.class, () -> stringToMap(invalidJson));
    }

    @Test
    void shouldThrowExceptionWhenInputIsNull() {
        assertThrows(IllegalArgumentException.class, () -> stringToMap(null));
    }
}