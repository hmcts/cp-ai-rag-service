package uk.gov.moj.cp.ai.util;

import static java.util.UUID.nameUUIDFromBytes;

import java.nio.charset.StandardCharsets;

public class RowKeyUtil {

    public static String generateRowKey(String generateFrom) {
        byte[] bytes = generateFrom.trim().getBytes(StandardCharsets.UTF_8);
        return nameUUIDFromBytes(bytes).toString();
    }
}
