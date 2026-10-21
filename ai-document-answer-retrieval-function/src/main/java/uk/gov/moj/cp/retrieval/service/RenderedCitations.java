package uk.gov.moj.cp.retrieval.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendered citations of one answer, kept behind bracket-free sentinels until {@link #expand}.
 * A rendered citation carries the filename verbatim, and a filename can look like a marker
 * ({@code [12345]}, {@code 2 notes.pdf}) or a citation, so no marker pass may see it. Not
 * thread-safe; one instance per answer.
 */
final class RenderedCitations {

    /** Private-use rather than NUL: {@link String#trim()} would strip a control character. */
    private static final char DELIMITER = '\uE000';

    private static final Pattern SENTINEL = Pattern.compile(DELIMITER + "CIT(\\d{1,6})" + DELIMITER);

    private final List<String> rendered = new ArrayList<>();

    /** Applied to model text first, so it can never forge a sentinel. */
    static String withoutSentinelDelimiter(final String text) {
        return text.indexOf(DELIMITER) < 0 ? text : text.replace(String.valueOf(DELIMITER), "");
    }

    String register(final String renderedCitation) {
        rendered.add(renderedCitation);
        return DELIMITER + "CIT" + (rendered.size() - 1) + DELIMITER;
    }

    /** One per sentinel occurrence, so a filename containing a citation cannot inflate it. */
    int count(final String text) {
        return (int) SENTINEL.matcher(text).results().count();
    }

    String expand(final String text) {
        return SENTINEL.matcher(text).replaceAll(
                match -> Matcher.quoteReplacement(rendered.get(Integer.parseInt(match.group(1)))));
    }
}
