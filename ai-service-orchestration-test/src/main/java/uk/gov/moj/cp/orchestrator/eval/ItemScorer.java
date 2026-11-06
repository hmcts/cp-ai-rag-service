package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.ExpectedFact;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Forbidden;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Near;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.RetrievalCheck;
import uk.gov.moj.cp.orchestrator.eval.FactMatcher.Span;

import java.util.ArrayList;
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

    /** Text in double or curly quotation marks; a straight single quote is left out because it is also an apostrophe. */
    private static final Pattern QUOTATION = Pattern.compile("[\"\u201c\u2018][^\"\u201d\u2019]*[\"\u201d\u2019]");
    private static final Pattern REJECTION = Pattern.compile(
            "\\b(?:ignor|disregard|contradict|not (?:be )?follow|incorrect|inaccurate|untrue|false\\b)");

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
        final List<RetrievedChunk> chunks = result.chunks() == null ? List.of() : result.chunks();

        final Set<Page> retrieved = new HashSet<>();
        chunks.forEach(c -> retrieved.add(new Page(fileOf(c.documentId()), c.pageNumber())));
        final Set<Page> cited = new HashSet<>();
        answer.citations().forEach(c -> c.pages().forEach(p -> cited.add(new Page(fileOf(c.documentId()), p))));
        final Set<Page> gold = new HashSet<>();
        item.expectedFacts().forEach(f -> gold.addAll(pagesOf(f)));

        final List<ExpectedFact> facts = item.expectedFacts();
        final List<String> missing = facts.stream().filter(f -> !stated(item, f, text, segments, lines)).map(f -> f.refs().get(0).factId()).toList();
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
                chunks.stream().map(c -> fileOf(c.documentId()) + "#" + c.pageNumber()).toList());
    }

    /**
     * A fact without an anchor may be stated anywhere, or reworded within one segment ({@code keywords}).
     * An anchored fact must be stated in a segment next to its anchor ({@link Near}).
     */
    private boolean stated(final EvalItem item, final ExpectedFact fact, final String text, final List<String> segments,
                           final List<String> lines) {
        if (fact.near() != null) {
            return lines.stream().anyMatch(l -> bound(item, fact, l));
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
        final List<Span> sentences = AnswerText.sentences(line);
        final List<Span> values = new ArrayList<>(valueSpans(fact, line));
        sentences.forEach(s -> FactMatcher.keywords(line.substring(s.start(), s.end()), fact.keywords())
                .ifPresent(k -> values.add(new Span(s.start() + k.start(), s.start() + k.end()))));
        final Near near = fact.near();
        final List<Span> anchors = near.ref() != null
                ? FactMatcher.spans(line, dataset.fact(near.ref()), List.of())
                : FactMatcher.spans(line, near.text().stream().map(FactMatcher::literal).toList());
        final List<Span> rivals = new ArrayList<>();
        near.rivals().forEach(r -> rivals.addAll(FactMatcher.spans(line, dataset.fact(r), List.of())));
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
     * A wrong-document fact counts anywhere. A claim counts in a segment that states it, unless the
     * statement is in quotation marks or the segment rejects it ("ignore", "not followed", "incorrect"):
     * an answer may quote an injected instruction in order to refuse it.
     */
    private boolean present(final Forbidden forbidden, final String text, final List<String> segments) {
        if (forbidden.ref() != null) {
            return !FactMatcher.spans(text, dataset.fact(forbidden.ref()), List.of()).isEmpty();
        }
        return segments.stream().anyMatch(s -> !REJECTION.matcher(s).find()
                && FactMatcher.spans(s, forbidden.patterns()).stream().anyMatch(span -> !quoted(s, span)));
    }

    private static boolean quoted(final String segment, final Span span) {
        return QUOTATION.matcher(segment).results().anyMatch(q -> q.start() < span.start() && q.end() > span.end());
    }

    private Set<Page> pagesOf(final ExpectedFact fact) {
        final Set<Page> pages = new HashSet<>();
        fact.refs().forEach(r -> dataset.fact(r).pages().forEach(p -> pages.add(new Page(r.file(), p))));
        return pages;
    }

    private static boolean retrievalOk(final RetrievalCheck check, final Set<Page> retrieved) {
        final Set<String> documents = new HashSet<>();
        retrieved.forEach(p -> documents.add(p.file()));
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
