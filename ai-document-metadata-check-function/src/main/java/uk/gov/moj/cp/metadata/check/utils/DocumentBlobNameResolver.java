package uk.gov.moj.cp.metadata.check.utils;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ofPattern;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.metadata.check.service.DocumentMetadataVariables.UPLOAD_FILE_DATE_FORMAT;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentBlobNameResolver {

    private static final Pattern BLOB_PATTERN = Pattern.compile("^([^_]+)_([0-9]{8})\\.[^.]+$");
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

    public String getDocumentId(final String blobName) {
        if (isNullOrEmpty(blobName)) {
            throw new IllegalArgumentException(format(INVALID_BLOB_NAME_ERROR_MSG, blobName));
        }

        final Matcher matcher = BLOB_PATTERN.matcher(blobName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format(INVALID_BLOB_NAME_ERROR_MSG, blobName));
        }

        return matcher.group(1);
    }
}
