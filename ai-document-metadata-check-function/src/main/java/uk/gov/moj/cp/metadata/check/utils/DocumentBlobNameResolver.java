package uk.gov.moj.cp.metadata.check.utils;

import static java.lang.String.format;
import static java.util.Objects.isNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DocumentBlobNameResolver {

    public static String getBlobName(final String documentId, final DateTimeFormatter dateTimeFormatter, final String uploadFileExtension) {
        final String today = LocalDateTime.now().format(dateTimeFormatter);
        return format("%s_%s.%s", documentId, today, uploadFileExtension);
    }

    public static String getDocumentId(final String blobName) {
        if (isNull(blobName) || !blobName.contains("_")) {
            throw new IllegalArgumentException(format("Invalid blobName: '%s' format, expected format is documentId_yyyyMMdd.fileExtension", blobName));
        }
        return blobName.substring(0, blobName.indexOf('_'));
    }
}
