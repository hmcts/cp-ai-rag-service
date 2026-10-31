package uk.gov.moj.cp.orchestrator.eval;

import uk.gov.moj.cp.orchestrator.eval.AnswerResult.RetrievedChunk;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.ExpectedFact;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.Forbidden;
import uk.gov.moj.cp.orchestrator.eval.EvalItem.RetrievalCheck;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores one answer against its dataset item. Documents are identified by file name throughout;
 * the runner supplies the documentId each file was ingested under.
 */
final class ItemScorer {

    private record Page(String file, int page) {
    }

    private final EvalDataset dataset;
    private final Map<String, String> fileByDocumentId;

    ItemScorer(final EvalDataset dataset, final Map<String, String> fileByDocumentId) {
        this.dataset = dataset;
        this.fileByDocumentId = Map.copyOf(fileByDocumentId);
    }

    ItemScore score(final EvalItem item, final AnswerResult result) {
        final AnswerText answer = AnswerText.of(result.llmResponse());
        final String prose = answer.prose();
        final List<RetrievedChunk> chunks = result.chunks() == null ? List.of() : result.chunks();

        final Set<Page> retrieved = new HashSet<>();
        chunks.forEach(c -> retrieved.add(new Page(fileOf(c.documentId()), c.pageNumber())));
        final Set<Page> cited = new HashSet<>();
        answer.citations().forEach(c -> c.pages().forEach(p -> cited.add(new Page(fileOf(c.documentId()), p))));
        final Set<Page> gold = new HashSet<>();
        item.expectedFacts().forEach(f -> gold.addAll(pagesOf(f)));

        final List<ExpectedFact> facts = item.expectedFacts();
        final List<String> missing = facts.stream().filter(f -> !stated(prose, f)).map(f -> f.refs().get(0).factId()).toList();
        final Double factRecall = facts.isEmpty() ? null : ratio((long) facts.size() - missing.size(), facts.size());
        final long factsWithGoldPage = facts.stream().filter(f -> pagesOf(f).stream().anyMatch(retrieved::contains)).count();
        final boolean refused = answer.isRefusal();

        final List<String> sections = item.citationsPerSection() ? answer.sections() : null;

        return new ItemScore(
                item.id(),
                result.status(),
                result.elapsedSeconds(),
                factRecall,
                factRecall == null ? null : factRecall >= item.minFactRecall(),
                missing,
                item.forbidden().stream().filter(f -> present(prose, f)).map(Forbidden::label).toList(),
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

    private boolean stated(final String prose, final ExpectedFact fact) {
        return fact.refs().stream().anyMatch(r -> FactMatcher.found(prose, dataset.fact(r), fact.accept()));
    }

    private boolean present(final String prose, final Forbidden forbidden) {
        return forbidden.ref() != null
                ? FactMatcher.found(prose, dataset.fact(forbidden.ref()), List.of())
                : FactMatcher.containsText(prose, forbidden.text());
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
