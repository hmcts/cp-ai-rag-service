package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.ExpectedFact;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Forbidden;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Near;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.RetrievalCheck;
import uk.gov.moj.cp.orchestrator.eval.FactMatcher.Span;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores one answer against its dataset item. Documents are identified by file name throughout;
 * the runner supplies the documentId each file was ingested under.
 */
final class ItemScorer {

    private record Page(String file, int page) {
    }

    /**
     * Text in double or curly quotation marks, or in straight single quotes that open after a non-letter
     * and close before one, so the apostrophes in {@code ltd's ... handbook's} do not pair up.
     */
    private static final Pattern QUOTATION = Pattern.compile(
            "[\"\u201c\u2018][^\"\u201d\u2019]*[\"\u201d\u2019]|(?<![\\p{L}\\p{N}])'[^']+'(?![\\p{L}\\p{N}])");
    /** A word rejecting a claim, within {@link #REJECTION_WINDOW} words after it ("... is incorrect", "... is an instruction to ignore"). */
    private static final Pattern REJECTION = Pattern.compile(
            "\\b(?:ignor|disregard|contradict|not (?:be )?follow|incorrect|inaccurate|untrue|false\\b|wrong\\b|not true\\b"
                    + "|misleading|not a record)");
    /** How many words after a claim a rejection may sit and still refer to it. */
    private static final int REJECTION_WINDOW = 5;
    /** Words just before a claim that deny or decline it ("it is not true that ...", "ignore the instruction to say that ..."). */
    private static final Pattern REJECTED_BEFORE = Pattern.compile(
            "(?:\\b(?:not true|untrue|false|incorrect|wrong|misleading|inaccurate)(?: to (?:say|claim|state|suggest))? that"
                    + "(?: [^.;:]{1,80}? (?:or|and|nor) that)?"
                    + "|\\b(?:ignor|disregard|not (?:be )?follow)[a-z]*(?: [a-z]+){0,4} (?:to (?:say|state|claim)|saying|stating|claiming)"
                    + "(?: that)?) $");

    private final EvalDataset dataset;
    private final Map<String, String> fileByDocumentId;

    ItemScorer(final EvalDataset dataset, final Map<String, String> fileByDocumentId) {
        this.dataset = dataset;
        this.fileByDocumentId = Map.copyOf(fileByDocumentId);
    }

    ItemScore score(final EvalItem item, final AnswerResult result) {
        final AnswerText answer = AnswerText.of(result.llmResponse());
        final String text = FactMatcher.normalise(answer.prose());
        final List<String> segments = answer.segments();
        final List<String> lines = answer.lines();
        final List<AnswerText.LineContext> contexts = answer.lineContexts();
        final List<RetrievedChunk> chunks = result.chunks() == null ? List.of() : result.chunks();

        final Set<Page> retrieved = new HashSet<>();
        chunks.forEach(c -> retrieved.add(new Page(fileOf(c.documentId()), c.pageNumber())));
        final Set<Page> cited = new HashSet<>();
        answer.citations().forEach(c -> c.pages().forEach(p -> cited.add(new Page(fileOf(c.documentId()), p))));
        final Set<Page> gold = new HashSet<>();
        item.expectedFacts().forEach(f -> gold.addAll(pagesOf(f)));

        final List<ExpectedFact> facts = item.expectedFacts();
        final List<String> missing = facts.stream().filter(f -> !stated(item, f, text, segments, lines, contexts))
                .map(f -> f.refs().get(0).factId()).toList();
        final Double factRecall = facts.isEmpty() ? null : ratio((long) facts.size() - missing.size(), facts.size());
        final long factsWithGoldPage = facts.stream().filter(f -> pagesOf(f).stream().anyMatch(retrieved::contains)).count();
        final boolean refused = answer.isNoDataSentinel() || (answer.opensWithRefusal() && missing.size() == facts.size());

        final List<String> sections = item.citationsPerSection() ? answer.sections() : null;

        return new ItemScore(
                item.id(),
                result.status(),
                result.elapsedSeconds(),
                factRecall,
                factRecall == null ? null : factRecall >= item.minFactRecall(),
                missing,
                item.forbidden().stream().filter(f -> present(f, text, segments)).map(Forbidden::label).toList(),
                refused,
                refused == item.shouldRefuse(),
                facts.isEmpty() ? null : ratio(factsWithGoldPage, facts.size()),
                item.retrieval() == null ? null : retrievalOk(item.retrieval(), retrieved),
                retrieved.stream().map(Page::file).distinct().sorted().toList(),
                (int) chunks.stream().filter(c -> !c.metadata().containsAll(item.metadataFilter())).count(),
                cited.size(),
                cited.isEmpty() || gold.isEmpty() ? null : ratio(cited.stream().filter(gold::contains).count(), cited.size()),
                !item.isLongAnswer(),
                sections == null ? null : sections.size(),
                sections == null ? null : (int) sections.stream().filter(s -> !AnswerText.hasCitation(s)).count(),
                result.reason() != null && !result.reason().isBlank(),
                result.reason(),
                chunks.size(),
                answer.raw().length(),
                chunks.stream().map(c -> fileOf(c.documentId()) + "#" + c.pageNumber()).toList(),
                item.chronological() ? outOfOrder(item, lines, contexts) : null);
    }

    /**
     * The date facts, taken earliest first, whose first statement comes on an earlier line than the
     * date before them. A fact's statement is where it is bound to its anchor, so a later line that
     * mentions an earlier date ("responds to the complaint of 18/04/2025") does not count against it.
     */
    private List<String> outOfOrder(final EvalItem item, final List<String> lines, final List<AnswerText.LineContext> contexts) {
        final List<ExpectedFact> dated = item.expectedFacts().stream()
                .filter(f -> dataset.fact(f.refs().get(0)).type() == CorpusFact.FactType.DATE)
                .sorted(Comparator.comparing(f -> LocalDate.parse(dataset.fact(f.refs().get(0)).value())))
                .toList();
        final List<String> out = new ArrayList<>();
        int latest = -1;
        for (final ExpectedFact fact : dated) {
            final int line = firstLine(item, fact, lines, contexts);
            if (line < 0) {
                continue;
            }
            if (line < latest) {
                out.add(fact.refs().get(0).factId());
            } else {
                latest = line;
            }
        }
        return out;
    }

    private int firstLine(final EvalItem item, final ExpectedFact fact, final List<String> lines,
                          final List<AnswerText.LineContext> contexts) {
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            final boolean here = fact.near() != null
                    ? bound(item, fact, line) || boundInContext(item, fact, contexts.get(i))
                    : !valueSpans(fact, line).isEmpty() || FactMatcher.keywords(line, fact.keywords()).isPresent();
            if (here) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Bound within a line's context by a value on the line itself, or on the heading it sits under: an
     * outcome under its date, or a bold date over its event. Values on the block's other lines are judged
     * at their own line. The line above lends only a fact anchor (an entry's date, whose rivals are the
     * other entries' dates); a text anchor such as an event word carries only from a heading, since a
     * line above usually holds a different event.
     */
    private boolean boundInContext(final EvalItem item, final ExpectedFact fact, final AnswerText.LineContext context) {
        return context != null && (context.underHeading() || fact.near().ref() != null)
                && bound(item, fact, context.text(), context.headEnd(), context.lineStart());
    }

    /**
     * A fact without an anchor may be stated anywhere, or reworded within one segment ({@code keywords}).
     * An anchored fact must be stated next to its anchor ({@link Near}) on one line, or on a line under
     * a heading that holds the anchor.
     */
    private boolean stated(final EvalItem item, final ExpectedFact fact, final String text, final List<String> segments,
                           final List<String> lines, final List<AnswerText.LineContext> contexts) {
        if (fact.near() != null) {
            for (int i = 0; i < lines.size(); i++) {
                if (bound(item, fact, lines.get(i)) || boundInContext(item, fact, contexts.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return !valueSpans(fact, text).isEmpty()
                || segments.stream().anyMatch(s -> FactMatcher.keywords(s, fact.keywords()).isPresent());
    }

    /**
     * Some statement of the value on the line belongs to the anchor. When the value's sentence holds the
     * anchor or a rival, the anchor must be in that sentence with no rival between them. When it holds
     * neither (a list item that gives the date in one sentence and the outcome in the next), the value
     * belongs to the nearest anchor or rival before it on the line, or failing that the nearest after it.
     */
    private boolean bound(final EvalItem item, final ExpectedFact fact, final String line) {
        return bound(item, fact, line, line.length(), 0);
    }

    /** As {@link #bound(EvalItem, ExpectedFact, String)}, counting only values that start before {@code headEnd} or from {@code lineStart}. */
    private boolean bound(final EvalItem item, final ExpectedFact fact, final String line, final int headEnd, final int lineStart) {
        final List<Span> sentences = AnswerText.sentences(line);
        final List<Span> values = new ArrayList<>(valueSpans(fact, line));
        sentences.forEach(s -> FactMatcher.keywords(line.substring(s.start(), s.end()), fact.keywords())
                .ifPresent(k -> values.add(new Span(s.start() + k.start(), s.start() + k.end()))));
        values.removeIf(v -> v.start() >= headEnd && v.start() < lineStart);
        final Near near = fact.near();
        final List<Span> anchors = near.ref() != null
                ? FactMatcher.spans(line, dataset.fact(near.ref()), List.of())
                : FactMatcher.spans(line, near.text().stream().map(FactMatcher::literal).toList());
        final List<Span> rivals = new ArrayList<>();
        near.rivals().forEach(r -> rivals.addAll(FactMatcher.spans(line, dataset.fact(r), List.of())));
        rivals.addAll(FactMatcher.spans(line, near.rivalText().stream().map(FactMatcher::literal).toList()));
        item.expectedFacts().stream()
                .filter(other -> other != fact && other.near() != null && other.near().ref() != null)
                .forEach(other -> rivals.addAll(FactMatcher.spans(line, dataset.fact(other.near().ref()), List.of())));

        for (final Span value : values) {
            final Span sentence = sentences.stream().filter(s -> s.start() <= value.start() && value.start() < s.end())
                    .findFirst().orElse(new Span(0, line.length()));
            final List<Span> sentenceAnchors = within(anchors, sentence);
            final List<Span> sentenceRivals = within(rivals, sentence);
            final boolean found = sentenceAnchors.isEmpty() && sentenceRivals.isEmpty()
                    ? nearestIsAnchor(value, anchors, rivals)
                    : sentenceAnchors.stream().anyMatch(a -> sentenceRivals.stream().noneMatch(r -> between(r, value, a)));
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static List<Span> within(final List<Span> spans, final Span range) {
        return spans.stream().filter(s -> s.start() >= range.start() && s.end() <= range.end()).toList();
    }

    private static boolean nearestIsAnchor(final Span value, final List<Span> anchors, final List<Span> rivals) {
        final Optional<Span> anchorBefore = anchors.stream().filter(a -> a.end() <= value.start()).max(Comparator.comparingInt(Span::end));
        final Optional<Span> rivalBefore = rivals.stream().filter(r -> r.end() <= value.start()).max(Comparator.comparingInt(Span::end));
        if (anchorBefore.isPresent() || rivalBefore.isPresent()) {
            return anchorBefore.isPresent() && (rivalBefore.isEmpty() || anchorBefore.get().end() > rivalBefore.get().end());
        }
        final Optional<Span> anchorAfter = anchors.stream().filter(a -> a.start() >= value.end()).min(Comparator.comparingInt(Span::start));
        final Optional<Span> rivalAfter = rivals.stream().filter(r -> r.start() >= value.end()).min(Comparator.comparingInt(Span::start));
        return anchorAfter.isPresent() && (rivalAfter.isEmpty() || anchorAfter.get().start() < rivalAfter.get().start());
    }

    private static boolean between(final Span rival, final Span x, final Span y) {
        final Span left = x.start() <= y.start() ? x : y;
        final Span right = left == x ? y : x;
        return rival.start() >= left.end() && rival.end() <= right.start();
    }

    private List<Span> valueSpans(final ExpectedFact fact, final String text) {
        final List<Span> spans = new ArrayList<>();
        fact.refs().forEach(r -> spans.addAll(FactMatcher.spans(text, dataset.fact(r), fact.accept())));
        return spans;
    }

    /**
     * A wrong-document fact counts anywhere. A claim counts where a segment states it, unless that
     * statement is in quotation marks, a rejection follows it within {@value #REJECTION_WINDOW} words
     * ("... is incorrect"), or the words just before it deny it ("it is not true that ..."): an answer
     * may quote an injected instruction in order to refuse it. A rejection elsewhere in the segment does
     * not exempt the claim.
     */
    private boolean present(final Forbidden forbidden, final String text, final List<String> segments) {
        if (forbidden.ref() != null) {
            return !FactMatcher.spans(text, dataset.fact(forbidden.ref()), List.of()).isEmpty();
        }
        return segments.stream().anyMatch(s -> FactMatcher.spans(s, forbidden.patterns()).stream()
                .anyMatch(span -> !quoted(s, span) && !rejected(s, span)));
    }

    private static boolean rejected(final String segment, final Span span) {
        final String after = String.join(" ", Arrays.stream(segment.substring(span.end()).strip().split(" "))
                .limit(REJECTION_WINDOW).toList());
        return REJECTION.matcher(after).find() || REJECTED_BEFORE.matcher(segment.substring(0, span.start())).find();
    }

    private static boolean quoted(final String segment, final Span span) {
        return QUOTATION.matcher(segment).results().anyMatch(q -> q.start() < span.start() && q.end() > span.end());
    }

    private Set<Page> pagesOf(final ExpectedFact fact) {
        final Set<Page> pages = new HashSet<>();
        fact.refs().forEach(r -> dataset.fact(r).pages().forEach(p -> pages.add(new Page(r.file(), p))));
        return pages;
    }

    /** A {@code mustInclude} entry is a document ({@code file}) or one page of it ({@code file#page}). */
    private static boolean retrievalOk(final RetrievalCheck check, final Set<Page> retrieved) {
        final Set<String> documents = new HashSet<>();
        retrieved.forEach(p -> documents.add(p.file()));
        retrieved.forEach(p -> documents.add(p.file() + "#" + p.page()));
        return documents.containsAll(check.mustInclude())
                && check.atMostOneOf().stream().allMatch(g -> g.stream().filter(documents::contains).count() <= 1);
    }

    /** An unknown documentId stays as-is, so a chunk from outside the corpus is still visible in the report. */
    private String fileOf(final String documentId) {
        return fileByDocumentId.getOrDefault(documentId, documentId);
    }

    private static double ratio(final long part, final long whole) {
        return Math.round(100.0 * part / whole) / 100.0;
    }
}
