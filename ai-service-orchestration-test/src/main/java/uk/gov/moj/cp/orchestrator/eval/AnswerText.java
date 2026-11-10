package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.FactMatcher.Span;

import java.util.ArrayList;
import java.util.Arrays;
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
            "::\\(Source: \\[([^\\]]{1,300})\\], Pages ([^|]{0,200})\\|([^|]{0,200})\\|documentId=([0-9a-fA-F-]{1,64})\\)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern BOLD_HEADING = Pattern.compile("\\*\\*[^*]+\\*\\*:?");

    /**
     * With chunks retrieved the model refuses in its own words, not with the sentinel (dry run,
     * 2026-10-27): a negation followed within a few words by a word saying the information is missing
     * ("do not contain", "don't mention", "was not found", "no reference to", "unable to find").
     */
    private static final Pattern REFUSAL = Pattern.compile(
            "(?:\\bno|\\bnot|n['\u2019]t|\\bcannot|\\bunable to|\\bnothing|\\bnone)(?: [\\w'\u2019-]+){0,4}? "
                    + "(?:contain|mention|stat|specif|provid|includ|find|found|available|record of|reference|information|detail"
                    + "|appear|list|show|give|indicat|say|said|disclos|identif|present|locat|cover|determin|record(?:ed)? in"
                    + "|in the (?:[a-z]+ )?(?:documents?|sources?|records?|files?|context|material))");
    /** How many sentences, after any heading lines, may come before the refusal (a lead-in such as "I checked the documents."). */
    private static final int REFUSAL_SENTENCES = 2;
    /** The space after a sentence or clause ending in {@code .}, {@code !}, {@code ?} or {@code ;}, in normalised text. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?;]) ");

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

    boolean isNoDataSentinel() {
        return raw.strip().equals(NO_DATA_SENTINEL);
    }

    /**
     * One of the first two sentences after any heading lines says the information is not there.
     * Citations do not decide it: a refusal may cite what it looked at, and a correct answer may be
     * uncited. The scorer also requires that no expected fact is stated, so an answer that gives the
     * facts and then says one detail is missing is not a refusal; an invented answer on a should-refuse
     * item is caught by the item's forbidden patterns, not here.
     */
    boolean opensWithRefusal() {
        return Arrays.stream(prose().split("\n")).filter(l -> !l.isBlank() && !isHeading(l))
                .map(FactMatcher::normalise)
                .flatMap(l -> sentences(l).stream().map(s -> l.substring(s.start(), s.end())))
                .limit(REFUSAL_SENTENCES)
                .anyMatch(s -> REFUSAL.matcher(s).find());
    }

    /** A markdown {@code #} heading, a bold-only line, or a short line ending with a colon. */
    static boolean isHeading(final String line) {
        final String s = line.strip();
        return s.startsWith("#") || BOLD_HEADING.matcher(s).matches() || (s.endsWith(":") && s.length() < MAX_HEADING_LENGTH);
    }

    /** The prose split into lines (a list item or table row each), normalised, blank ones dropped. */
    List<String> lines() {
        return Arrays.stream(prose().split("\n")).map(FactMatcher::normalise).filter(s -> !s.isEmpty()).toList();
    }

    /** For each line of {@link #lines()}, whether it is a heading line. */
    List<Boolean> headingLines() {
        return Arrays.stream(prose().split("\n")).filter(l -> !FactMatcher.normalise(l).isEmpty()).map(AnswerText::isHeading).toList();
    }

    /** A line in the context of the lines above it that it may take an anchor from. */
    record LineContext(String text, int headEnd, int lineStart, boolean underHeading) {
    }

    /**
     * For each line of {@link #lines()}, the lines above it that it may take an anchor from, joined with
     * {@code ; } and normalised: under a heading, the heading and every line of its block up to and
     * including this one ("Most recent finding:" carries to the list under it, with the block's earlier
     * lines still in place as rivals); otherwise the line just above it in the same paragraph (a date line
     * followed by its outcome line). Null for a heading line and for a paragraph's first line.
     * {@code lineStart} is where this line starts; {@code headEnd} is where the heading ends, or 0 for
     * the line above, which lends its anchor but not its values (a date above must not take this line's event).
     */
    List<LineContext> lineContexts() {
        final List<LineContext> contexts = new ArrayList<>();
        List<String> block = null;
        String previous = null;
        for (final String line : prose().split("\n")) {
            final String normalised = FactMatcher.normalise(line);
            if (normalised.isEmpty()) {
                previous = null;
                continue;
            }
            if (isHeading(line)) {
                block = new ArrayList<>(List.of(normalised));
                contexts.add(null);
            } else if (block != null) {
                block.add(normalised);
                contexts.add(context(block, true));
            } else {
                contexts.add(previous == null ? null : context(List.of(previous, normalised), false));
            }
            previous = normalised;
        }
        return contexts;
    }

    private static LineContext context(final List<String> lines, final boolean heading) {
        final String text = String.join("; ", lines);
        return new LineContext(text, heading ? lines.get(0).length() : 0, text.length() - lines.get(lines.size() - 1).length(), heading);
    }

    /** Where each sentence of a normalised line sits (a {@code ;} also ends one). */
    static List<Span> sentences(final String line) {
        final List<Span> sentences = new ArrayList<>();
        int start = 0;
        final Matcher m = SENTENCE_BREAK.matcher(line);
        while (m.find()) {
            sentences.add(new Span(start, m.start()));
            start = m.end();
        }
        sentences.add(new Span(start, line.length()));
        return sentences;
    }

    /** The prose split into lines and then sentences, normalised. Key words and forbidden claims are judged within one segment. */
    List<String> segments() {
        return lines().stream().flatMap(l -> sentences(l).stream().map(s -> l.substring(s.start(), s.end()))).toList();
    }

    /**
     * Splits on heading lines: markdown {@code #}, a bold-only line, or a short line ending with a colon.
     * Text before the first heading (an intro line) is not a section; an answer with no heading is one section.
     */
    List<String> sections() {
        final List<String> sections = new ArrayList<>();
        final List<String> current = new ArrayList<>();
        boolean headed = false;
        for (final String line : raw.split("\n", -1)) {
            final boolean heading = isHeading(line);
            if (heading && !headed) {
                current.clear();
                headed = true;
            } else if (heading && !current.isEmpty()) {
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
