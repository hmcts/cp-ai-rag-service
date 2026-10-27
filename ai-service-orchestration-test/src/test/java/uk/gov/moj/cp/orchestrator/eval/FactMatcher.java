package uk.gov.moj.cp.orchestrator.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether an answer states a manifest fact. Both sides are normalised the same way
 * ({@link #normalise}), then each fact type has one matcher that accepts the usual written forms,
 * so every item gets the same tolerance without hand-written patterns.
 */
final class FactMatcher {

    private static final Pattern SPACED_THOUSANDS = Pattern.compile("(\\d),\\s+(\\d{3})(?!\\d)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private FactMatcher() {
    }

    /**
     * Unicode NFKC ({@code ℃} → {@code °C}), lower case, markdown bold removed, the space Document
     * Intelligence inserts after a thousands comma ({@code 4, 149}) removed, whitespace collapsed.
     * Only a three-digit group is joined, so {@code April 7, 2025} keeps its space.
     */
    static String normalise(final String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace("*", "");
        s = SPACED_THOUSANDS.matcher(s).replaceAll("$1,$2");
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    static boolean found(final String answer, final CorpusFact fact, final List<String> accept) {
        final String text = normalise(answer);
        if (accept.stream().anyMatch(a -> text.contains(normalise(a)))) {
            return true;
        }
        return switch (fact.type()) {
            case DATE -> datePatterns(LocalDate.parse(fact.value())).stream().anyMatch(p -> p.matcher(text).find());
            case MONEY -> moneyPattern(new BigDecimal(fact.value())).matcher(text).find();
            case NUMBER -> Pattern.compile("(?<!\\d)" + Pattern.quote(normalise(fact.value())) + "(?!\\d)").matcher(text).find();
            case TEXT -> text.contains(normalise(fact.value()));
        };
    }

    static boolean containsText(final String answer, final String text) {
        return normalise(answer).contains(normalise(text));
    }

    private static List<Pattern> datePatterns(final LocalDate date) {
        final int day = date.getDayOfMonth();
        final int month = date.getMonthValue();
        final String fullName = date.getMonth().name().toLowerCase(Locale.ROOT);
        final String names = "(?:" + fullName + "|" + fullName.substring(0, 3) + (date.getMonth() == Month.SEPTEMBER ? "|sept" : "") + ")";
        final String dayOfMonth = "0?" + day + "(?:st|nd|rd|th)?";
        final String year = "(?:" + date.getYear() + "|'?" + String.format("%02d", date.getYear() % 100) + ")";

        final List<Pattern> patterns = new ArrayList<>();
        patterns.add(Pattern.compile("\\b0?" + day + "[/.\\-]0?" + month + "[/.\\-]" + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + dayOfMonth + " (?:of )?" + names + ",? ?" + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + names + " " + dayOfMonth + ",? ?" + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + String.format("%d-%02d-%02d", date.getYear(), month, day) + "\\b"));
        return patterns;
    }

    /** {@code £4,149}, {@code £4149}, {@code £ 4,149} and {@code £4,149.00}, but not {@code £41,490}; a following comma is punctuation. */
    private static Pattern moneyPattern(final BigDecimal amount) {
        final BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        final String grouped = String.format(Locale.UK, "%,d", whole.longValueExact()).replace(",", ",?");
        final boolean hasPence = amount.compareTo(whole) != 0;
        final String pence = hasPence
                ? "\\." + amount.subtract(whole).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString()
                : "(?:\\.00)?";
        return Pattern.compile("£ ?" + grouped + pence + "(?!\\d|,\\d)");
    }
}
