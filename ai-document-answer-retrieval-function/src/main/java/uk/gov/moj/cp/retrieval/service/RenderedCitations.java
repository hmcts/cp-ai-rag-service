package uk.gov.moj.cp.retrieval.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the citations rendered while one answer is post-processed, behind opaque sentinels.
 *
 * <p>A rendered citation carries the retrieved filename verbatim, and a filename can look like a
 * citation marker ({@code [12345]}, {@code 2 notes.pdf}) or even like a rendered citation. If the
 * rendered string went straight into the answer text, the later substitution, stripping and
 * counting passes would act on it. Instead, merge and substitution insert a sentinel
 * ({@code U+E000 CIT<n> U+E000}) — no brackets, no digits in brackets, so no marker pattern can
 * match it — and {@link #expand} swaps the rendered strings in as the very last step. The model's
 * text can never contain a sentinel: the delimiter character is removed from it before
 * processing.
 *
 * <p>Not thread-safe; one instance per answer.
 */
final class RenderedCitations {

    /**
     * Delimiter of a sentinel: a Unicode private-use character, stripped from model text by
     * {@link #withoutSentinelDelimiter}. Not a control character, so {@link String#trim()} keeps it.
     */
    private static final char DELIMITER = '\uE000';

    private static final Pattern SENTINEL = Pattern.compile(DELIMITER + "CIT(\\d{1,6})" + DELIMITER);

    private final List<String> rendered = new ArrayList<>();

    /** The input with every sentinel delimiter removed, so model text cannot forge a sentinel. */
    static String withoutSentinelDelimiter(final String text) {
        return text.indexOf(DELIMITER) < 0 ? text : text.replace(String.valueOf(DELIMITER), "");
    }

    /** Registers one rendered citation and returns the sentinel that stands for it in the text. */
    String register(final String renderedCitation) {
        rendered.add(renderedCitation);
        return DELIMITER + "CIT" + (rendered.size() - 1) + DELIMITER;
    }

    /** Citations present in {@code text}: one per sentinel occurrence (an id used twice counts twice). */
    int count(final String text) {
        return (int) SENTINEL.matcher(text).results().count();
    }

    /** {@code text} with every sentinel replaced by the citation it stands for. */
    String expand(final String text) {
        return SENTINEL.matcher(text).replaceAll(
                match -> Matcher.quoteReplacement(rendered.get(Integer.parseInt(match.group(1)))));
    }
}
