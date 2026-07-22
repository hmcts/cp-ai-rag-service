package uk.gov.moj.cp.metadata.check.utils;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

public class DocumentBlobNameResolverTest {

    private final DocumentBlobNameResolver resolver = new DocumentBlobNameResolver();

    @Test
    void getDocumentId_shouldReturnDocumentId_whenValidBlobName() {
        final String blobName = "doc123_20240101.pdf";

        final String result = resolver.getDocumentId(blobName);

        assertThat("doc123", is(result));
    }

    @Test
    void getDocumentId_shouldThrowException_whenBlobNameIsNull() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId(null));
    }

    @Test
    void getDocumentId_shouldThrowException_whenBlobNameIsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId(""));
    }

    @Test
    void getDocumentId_shouldThrowException_whenMissingUnderscore() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId("doc12320240101.pdf"));
    }

    @Test
    void getDocumentId_shouldThrowException_whenInvalidDateFormat() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId("doc123_20241.pdf"));
    }

    @Test
    void getDocumentId_shouldThrowException_whenMissingExtension() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId("doc123_20240101"));
    }

    @Test
    void getDocumentId_shouldThrowException_whenExtraUnderscoresInDocumentId() {
        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId("doc_123_20240101.pdf"));
    }

    @Test
    void getBlobName_shouldGenerateCorrectFormat() {
        final String documentId = "doc123";
        final String extension = "pdf";

        final String blobName = resolver.getBlobName(documentId, extension);

        assertThat(blobName.startsWith(documentId + "_"), is(true));
        assertThat(blobName.endsWith("." + extension), is(true));

        assertThat(blobName.matches("^doc123_[0-9]{8}\\.pdf$"), is(true));
    }

    @Test
    void getBlobName_shouldContainTodayDateInConfiguredFormat() {
        final String documentId = "doc123";
        final String extension = "txt";
        final String expectedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        final String blobName = resolver.getBlobName(documentId, extension);

        assertThat(blobName.contains(expectedDate), is(true));
    }

    @Test
    void getBlobName_shouldReturnClientPrefixedFormat_whenClientIdPresent() {
        final String clientId = "client-1";
        final String documentId = "doc123";
        final String extension = "pdf";

        final String blobName = resolver.getBlobName(clientId, documentId, extension);

        assertThat(blobName.matches("^c=client-1/doc123_[0-9]{8}\\.pdf$"), is(true));
    }

    @Test
    void getBlobName_shouldReturnFlatFormat_whenClientIdIsNull() {
        final String documentId = "doc123";
        final String extension = "pdf";

        final String prefixedOverloadResult = resolver.getBlobName(null, documentId, extension);
        final String flatResult = resolver.getBlobName(documentId, extension);

        assertThat(prefixedOverloadResult, is(flatResult));
        assertThat(prefixedOverloadResult.matches("^doc123_[0-9]{8}\\.pdf$"), is(true));
    }

    @Test
    void getBlobName_shouldReturnFlatFormat_whenClientIdIsEmpty() {
        final String documentId = "doc123";
        final String extension = "pdf";

        final String prefixedOverloadResult = resolver.getBlobName("", documentId, extension);

        assertThat(prefixedOverloadResult.matches("^doc123_[0-9]{8}\\.pdf$"), is(true));
    }

    @Test
    void getDocumentId_shouldReturnDocumentId_whenClientPrefixedBlobName() {
        final String blobName = "c=client-1/doc123_20240101.pdf";

        final String result = resolver.getDocumentId(blobName);

        assertThat(result, is("doc123"));
    }

    @Test
    void getClientId_shouldReturnClientId_whenClientPrefixedBlobName() {
        final String blobName = "c=client-1/doc123_20240101.pdf";

        final String result = resolver.getClientId(blobName);

        assertThat(result, is("client-1"));
    }

    @Test
    void getClientId_shouldReturnNull_whenFlatBlobName() {
        final String blobName = "doc123_20240101.pdf";

        final String result = resolver.getClientId(blobName);

        assertThat(result, is(nullValue()));
    }

    @Test
    void getDocumentId_shouldThrowException_whenNestedPrefixedBlobName() {
        // A name announcing a client prefix must parse as exactly one prefix segment — the permissive
        // flat pattern must not swallow a malformed prefixed path as a garbage documentId.
        final String blobName = "c=client-a/c=client-b/doc123_20240101.pdf";

        assertThrows(IllegalArgumentException.class, () -> resolver.getDocumentId(blobName));
    }

    @Test
    void getClientId_shouldReturnNull_whenNestedPrefixedBlobName() {
        final String blobName = "c=client-a/c=client-b/doc123_20240101.pdf";

        final String result = resolver.getClientId(blobName);

        assertThat(result, is(nullValue()));
    }
}
