package uk.gov.moj.cp.metadata.check.utils;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.moj.cp.ai.storage.BlobNamespace.CLIENT_PREFIX_MARKER;
import static uk.gov.moj.cp.ai.storage.BlobNamespace.applyClientPrefix;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.metadata.check.service.DocumentMetadataVariables.UPLOAD_FILE_DATE_FORMAT;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentBlobNameResolver {

    private static final Pattern BLOB_PATTERN = Pattern.compile("^([^_]+)_([0-9]{8})\\.[^.]+$");
    private static final Pattern PREFIXED_BLOB_PATTERN =
            Pattern.compile("^" + Pattern.quote(CLIENT_PREFIX_MARKER) + "([^/]+)/([^/_]+)_([0-9]{8})\\.[^.]+$");
    private static final String INVALID_BLOB_NAME_ERROR_MSG = "Invalid blobName: '%s' format, expected format is documentId_yyyyMMdd.fileExtension";
    private static final String DEFAULT_DATETIME_FORMAT = "yyyyMMdd";

    private final DateTimeFormatter dateTimeFormatter;

    public DocumentBlobNameResolver() {
        dateTimeFormatter = ofPattern(getRequiredEnv(UPLOAD_FILE_DATE_FORMAT, DEFAULT_DATETIME_FORMAT));
    }

    public String getBlobName(final String documentId, final String uploadFileExtension) {
        final String today = LocalDateTime.now().format(dateTimeFormatter);
        return format("%s_%s.%s", documentId, today, uploadFileExtension);
    }

    /**
     * Builds a client-namespaced blob name when a clientId is supplied, falling back to the flat
     * shape otherwise. A non-empty clientId yields {@code c={clientId}/{documentId}_{yyyyMMdd}.{ext}};
     * a null/empty clientId yields the flat shape.
     */
    public String getBlobName(final String clientId, final String documentId, final String uploadFileExtension) {
        return applyClientPrefix(clientId, getBlobName(documentId, uploadFileExtension));
    }

    public String getDocumentId(final String blobName) {
        if (isNullOrEmpty(blobName)) {
            throw new IllegalArgumentException(format(INVALID_BLOB_NAME_ERROR_MSG, blobName));
        }

        final Matcher prefixedMatcher = PREFIXED_BLOB_PATTERN.matcher(blobName);
        if (prefixedMatcher.matches()) {
            return prefixedMatcher.group(2);
        }
        // A name that announces a client prefix must parse as one — the flat pattern's permissive
        // documentId group would otherwise swallow the whole prefixed path as a garbage documentId.
        if (blobName.startsWith(CLIENT_PREFIX_MARKER)) {
            throw new IllegalArgumentException(format(INVALID_BLOB_NAME_ERROR_MSG, blobName));
        }

        final Matcher matcher = BLOB_PATTERN.matcher(blobName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format(INVALID_BLOB_NAME_ERROR_MSG, blobName));
        }

        return matcher.group(1);
    }

    /**
     * Returns the clientId encoded in a namespaced blob name, or {@code null} when the name has no
     * prefix (defer to the Table row for ownership).
     */
    public String getClientId(final String blobName) {
        if (isNullOrEmpty(blobName)) {
            return null;
        }

        final Matcher prefixedMatcher = PREFIXED_BLOB_PATTERN.matcher(blobName);
        if (prefixedMatcher.matches()) {
            return prefixedMatcher.group(1);
        }

        return null;
    }
}
