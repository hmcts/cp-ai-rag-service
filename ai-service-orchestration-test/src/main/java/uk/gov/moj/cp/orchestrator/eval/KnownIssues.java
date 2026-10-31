package uk.gov.moj.cp.orchestrator.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The known-issues register ({@code eval/known-issues.json}): checks that are known to fail on an
 * item and are left out of the gate until fixed. Each entry names one item and one check, so the
 * item's other checks stay gated. Loading validates every entry against the dataset, so a stale
 * entry (renamed item, removed fact) fails before any document is ingested.
 *
 * <p>Checks that can be registered: {@code fact:<factId>}, {@code forbidden:<label>},
 * {@code refusal}, {@code retrieval}, {@code outside-filter}, {@code citation-degraded} and
 * {@code sections-uncited}. An error or timeout can never be a known issue.</p>
 */
public record KnownIssues(List<KnownIssue> issues) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> PLAIN_CHECKS =
            List.of("refusal", "retrieval", "outside-filter", "citation-degraded", "sections-uncited");

    public record KnownIssue(String item, String check, String jira, String reason) {
    }

    /** Loads the register next to the dataset; no file means no known issues. */
    public static KnownIssues load(final EvalDataset dataset) {
        final Path file = dataset.directory().resolve("known-issues.json");
        if (!file.toFile().exists()) {
            return new KnownIssues(List.of());
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        final List<KnownIssue> issues = new ArrayList<>();
        root.path("issues").forEach(i -> issues.add(new KnownIssue(i.path("item").asText(), i.path("check").asText(),
                i.path("jira").asText(), i.path("reason").asText())));
        return of(dataset, issues);
    }

    static KnownIssues of(final EvalDataset dataset, final List<KnownIssue> issues) {
        final List<String> invalid = new ArrayList<>();
        for (final KnownIssue issue : issues) {
            if (issue.jira().isBlank() || issue.reason().isBlank()) {
                invalid.add(issue.item() + " " + issue.check() + ": needs a jira and a reason");
            }
            final EvalItem item = dataset.items().stream().filter(i -> i.id().equals(issue.item())).findFirst().orElse(null);
            if (item == null) {
                invalid.add(issue.item() + ": no such dataset item");
            } else if (!checkExists(item, issue.check())) {
                invalid.add(issue.item() + " " + issue.check() + ": not a check of this item");
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Invalid known-issues entries: " + invalid);
        }
        return new KnownIssues(List.copyOf(issues));
    }

    boolean isKnown(final String itemId, final String check) {
        return issues.stream().anyMatch(i -> i.item().equals(itemId) && i.check().equals(check));
    }

    List<KnownIssue> forItem(final String itemId) {
        return issues.stream().filter(i -> i.item().equals(itemId)).toList();
    }

    private static boolean checkExists(final EvalItem item, final String check) {
        if (check.startsWith("fact:")) {
            final String factId = check.substring("fact:".length());
            return item.expectedFacts().stream().anyMatch(f -> f.refs().get(0).factId().equals(factId));
        }
        if (check.startsWith("forbidden:")) {
            final String label = check.substring("forbidden:".length());
            return item.forbidden().stream().anyMatch(f -> f.label().equals(label));
        }
        return PLAIN_CHECKS.contains(check);
    }
}
