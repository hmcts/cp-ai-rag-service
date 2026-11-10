package uk.gov.moj.cp.orchestrator.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * {@code refusal}, {@code retrieval}, {@code outside-filter}, {@code citation-degraded},
 * {@code sections-uncited} and {@code order}. An error or timeout can never be a known issue.</p>
 *
 * <p>Every entry has an {@code expires} date. From the day after it, the entry no longer exempts its
 * check, which is gated again, and the run log names it as expired; the build does not fail on the
 * date itself. A {@code citation-degraded} entry may set {@code reasonContains}, so only guard
 * reasons containing that text are exempt. An entry may not ungate an item altogether: the only
 * expected fact of an item, every expected fact of an item, or the refusal check of a should-refuse
 * item cannot be registered.</p>
 */
public record KnownIssues(List<KnownIssue> issues, LocalDate today) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FACT_PREFIX = "fact:";
    private static final String CITATION_DEGRADED = "citation-degraded";
    private static final String REFUSAL = "refusal";
    private static final List<String> PLAIN_CHECKS =
            List.of(REFUSAL, "retrieval", "outside-filter", CITATION_DEGRADED, "sections-uncited", "order");

    /**
     * @param expires        the last day the entry exempts its check
     * @param reasonContains optional, {@code citation-degraded} only: the exemption covers only a guard reason containing this text
     */
    public record KnownIssue(String item, String check, String jira, String reason, LocalDate expires, String reasonContains) {

        boolean expiredOn(final LocalDate day) {
            return day.isAfter(expires);
        }
    }

    /** Loads the register next to the dataset; no file means no known issues. */
    public static KnownIssues load(final EvalDataset dataset) {
        final Path file = dataset.directory().resolve("known-issues.json");
        if (!file.toFile().exists()) {
            return new KnownIssues(List.of(), LocalDate.now());
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(file.toFile());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        final List<KnownIssue> issues = new ArrayList<>();
        root.path("issues").forEach(i -> issues.add(new KnownIssue(i.path("item").asText(), i.path("check").asText(),
                i.path("jira").asText(), i.path("reason").asText(), date(i.path("expires").asText()),
                i.hasNonNull("reasonContains") ? i.get("reasonContains").asText() : null)));
        return of(dataset, issues, LocalDate.now());
    }

    static KnownIssues of(final EvalDataset dataset, final List<KnownIssue> issues) {
        return of(dataset, issues, LocalDate.now());
    }

    static KnownIssues of(final EvalDataset dataset, final List<KnownIssue> issues, final LocalDate today) {
        final List<String> invalid = new ArrayList<>();
        for (final KnownIssue issue : issues) {
            final String name = issue.item() + " " + issue.check();
            if (issue.jira().isBlank() || issue.reason().isBlank() || issue.expires() == null) {
                invalid.add(name + ": needs a jira, a reason and an expires date (yyyy-mm-dd)");
            }
            if (issue.reasonContains() != null && !CITATION_DEGRADED.equals(issue.check())) {
                invalid.add(name + ": reasonContains applies only to citation-degraded");
            }
            final EvalItem item = dataset.items().stream().filter(i -> i.id().equals(issue.item())).findFirst().orElse(null);
            if (item == null) {
                invalid.add(issue.item() + ": no such dataset item");
            } else if (!checkExists(item, issue.check())) {
                invalid.add(name + ": not a check of this item");
            } else if (REFUSAL.equals(issue.check()) && item.shouldRefuse()) {
                invalid.add(name + ": would leave a should-refuse item with no refusal check");
            }
        }
        dataset.items().forEach(item -> {
            final long knownFacts = issues.stream()
                    .filter(i -> i.item().equals(item.id()) && i.check().startsWith(FACT_PREFIX)).count();
            if (knownFacts > 0 && knownFacts >= item.expectedFacts().size()) {
                invalid.add(item.id() + ": known issues would leave no expected fact gated");
            }
        });
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Invalid known-issues entries: " + invalid);
        }
        return new KnownIssues(List.copyOf(issues), today);
    }

    /** The check is exempt for this item today, for any guard reason. */
    boolean isKnown(final String itemId, final String check) {
        return isKnown(itemId, check, null);
    }

    /** The check is exempt for this item today and, for a {@code reasonContains} entry, for this guard reason. */
    boolean isKnown(final String itemId, final String check, final String reason) {
        return active(itemId).stream().anyMatch(i -> i.check().equals(check)
                && (i.reasonContains() == null || reason != null && reason.contains(i.reasonContains())));
    }

    /** The item's entries that still exempt their check. */
    List<KnownIssue> forItem(final String itemId) {
        return active(itemId);
    }

    /** The item's entries past their expiry date, whose checks are gated again. */
    List<KnownIssue> expiredFor(final String itemId) {
        return issues.stream().filter(i -> i.item().equals(itemId) && i.expiredOn(today)).toList();
    }

    private List<KnownIssue> active(final String itemId) {
        return issues.stream().filter(i -> i.item().equals(itemId) && !i.expiredOn(today)).toList();
    }

    private static LocalDate date(final String text) {
        try {
            return text.isBlank() ? null : LocalDate.parse(text);
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    private static boolean checkExists(final EvalItem item, final String check) {
        if (check.startsWith(FACT_PREFIX)) {
            final String factId = check.substring(FACT_PREFIX.length());
            return item.expectedFacts().stream().anyMatch(f -> f.refs().get(0).factId().equals(factId));
        }
        if (check.startsWith("forbidden:")) {
            final String label = check.substring("forbidden:".length());
            return item.forbidden().stream().anyMatch(f -> f.label().equals(label));
        }
        return PLAIN_CHECKS.contains(check);
    }
}
