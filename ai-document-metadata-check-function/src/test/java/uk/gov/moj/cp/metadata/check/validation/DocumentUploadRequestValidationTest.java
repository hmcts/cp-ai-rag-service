package uk.gov.moj.cp.metadata.check.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import uk.gov.hmcts.cp.openapi.model.DocumentUploadRequest;
import uk.gov.hmcts.cp.openapi.model.MetadataFilter;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class DocumentUploadRequestValidationTest {
    private DocumentUploadRequest createValidRequest() {
        final MetadataFilter filter = new MetadataFilter();
        filter.setKey("key");
        filter.setValue("val");
        // initialize required fields in MetadataFilter if needed
        return new DocumentUploadRequest(
                UUID.randomUUID().toString(),
                "Test Document",
                List.of(filter)
        );
    }

    @Test
    void shouldReturnNoErrors_whenRequestIsValid() {
        final DocumentUploadRequest request = createValidRequest();

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldFail_whenDocumentIdIsNull() {
        final DocumentUploadRequest request = createValidRequest();
        request.setDocumentId(null);

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.stream().anyMatch(e -> e.contains("documentId")), is(true));
    }

    @Test
    void shouldFail_whenDocumentIdIsInvalidUUID() {
        final DocumentUploadRequest request = createValidRequest();
        request.setDocumentId("invalid-uuid");

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.stream().anyMatch(e -> e.contains("documentId")), is(true));
    }

    @Test
    void shouldFail_whenDocumentNameIsTooLong() {
        final DocumentUploadRequest request = createValidRequest();
        request.setDocumentName("A".repeat(51));

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.stream().anyMatch(e -> e.contains("documentName")), is(true));
    }

    @Test
    void shouldFail_whenDocumentNameIsNull() {
        final DocumentUploadRequest request = createValidRequest();
        request.setDocumentName(null);

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.stream().anyMatch(e -> e.contains("documentName")), is(true));
    }

    @Test
    void shouldFail_whenMetadataFilterIsNull() {
        final DocumentUploadRequest request = createValidRequest();
        request.setMetadataFilter(null);

        final List<String> errors = RequestValidator.validate(request);

        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.stream().anyMatch(e -> e.contains("metadataFilter")), is(true));
    }

    @Test
    void shouldCascadeValidation_whenMetadataFilterIsInvalid() {
        MetadataFilter invalidFilter = new MetadataFilter();

        final DocumentUploadRequest request = new DocumentUploadRequest(
                UUID.randomUUID().toString(),
                "Valid Name",
                List.of(invalidFilter)
        );

        final List<String> errors = RequestValidator.validate(request);
        assertThat(errors.isEmpty(), is(false));
        assertThat(errors.size(), is(2));
        assertThat(errors, containsInAnyOrder("metadataFilter[0].value: must not be null",
                "metadataFilter[0].key: must not be null"));

    }
}