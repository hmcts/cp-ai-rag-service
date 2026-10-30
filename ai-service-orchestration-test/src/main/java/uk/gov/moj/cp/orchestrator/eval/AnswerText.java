package uk.gov.moj.cp.orchestrator.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An answer as rendered by the service, split into its citations and the prose around them, so
 * facts are matched against the prose only and citations are scored separately.
 */
final class AnswerText {

    /** The no-evidence answer the service returns when retrieval finds nothing. */
    static final String NO_DATA_SENTINEL = "No data available matching the query.";

    /** {@code ::(Source: [file], Pages <compressed>|<individual>|documentId=<uuid>)}, as {@code CitationProcessor} renders it. */
    private static final Pattern CITATION = Pattern.compile(
            "::\\(Source: \\[(.*?)\\], Pages ([^|]*)\\|([^|]*)\\|documentId=([0-9a-fA-F-]+)\\)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern BOLD_HEADING = Pattern.compile("\\*\\*[^*]+\\*\\*:?");

    /**
     * With chunks retrieved the model refuses in its own words, not with the sentinel (dry run,
     * 2026-10-27). An uncited answer using one of these phrases counts as a refusal.
     */
    private static final List<String> REFUSAL_PHRASES = List.of("no information", "does not contain", "do not contain",
            "not contain any", "not available", "unable to find", "could not find", "cannot find", "not mentioned",
            "not provided", "no details", "not specified", "no mention", "not include");

    private static final int MAX_HEADING_LENGTH = 60;

    private final String raw;
    private final List<Citation> citations;

    record Citation(String fileName, Set<Integer> pages, String documentId) {
    }

    private AnswerText(final String raw) {
        this.raw = raw;
        final List<Citation> found = new ArrayList<>();
        final Matcher m = CITATION.matcher(raw);
        while (m.find()) {
            final String individual = m.group(3).isBlank() ? m.group(2) : m.group(3);
            final Set<Integer> pages = new LinkedHashSet<>();
            NUMBER.matcher(individual).results().forEach(r -> pages.add(Integer.parseInt(r.group())));
            found.add(new Citation(m.group(1), Set.copyOf(pages), m.group(4)));
        }
        this.citations = List.copyOf(found);
    }

    static AnswerText of(final String answer) {
        return new AnswerText(answer == null ? "" : answer);
    }

    String raw() {
        return raw;
    }

    List<Citation> citations() {
        return citations;
    }

    String prose() {
        return CITATION.matcher(raw).replaceAll(" ");
    }

    boolean isRefusal() {
        if (raw.strip().equals(NO_DATA_SENTINEL)) {
            return true;
        }
        final String prose = FactMatcher.normalise(prose());
        return citations.isEmpty() && REFUSAL_PHRASES.stream().anyMatch(prose::contains);
    }

    /** Splits on heading lines: markdown {@code #}, a bold-only line, or a short line ending with a colon. */
    List<String> sections() {
        final List<String> sections = new ArrayList<>();
        final List<String> current = new ArrayList<>();
        for (final String line : raw.split("\n", -1)) {
            final String s = line.strip();
            final boolean heading = s.startsWith("#") || BOLD_HEADING.matcher(s).matches()
                    || (s.endsWith(":") && s.length() < MAX_HEADING_LENGTH);
            if (heading && !current.isEmpty()) {
                sections.add(String.join("\n", current));
                current.clear();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            sections.add(String.join("\n", current));
        }
        return sections.stream().filter(s -> !s.isBlank()).toList();
    }

    static boolean hasCitation(final String text) {
        return CITATION.matcher(text).find();
    }
}
