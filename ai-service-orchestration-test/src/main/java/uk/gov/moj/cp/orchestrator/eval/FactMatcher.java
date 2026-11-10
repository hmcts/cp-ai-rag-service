package uk.gov.moj.cp.orchestrator.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether an answer states a manifest fact. Both sides are normalised the same way
 * ({@link #normalise}), then each fact type has one matcher that accepts the usual written forms,
 * so every item gets the same tolerance without hand-written patterns.
 */
final class FactMatcher {

    private static final Pattern SPACED_THOUSANDS = Pattern.compile("(\\d),\\s+(\\d{3})(?!\\d)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Hyphen and dash variants models emit (non-breaking hyphen, en/em dash, minus) read as {@code -}. */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212]");
    /** Between the parts of a numeric date: {@code /}, {@code .} or {@code -}. */
    private static final String DATE_SEPARATOR = "[/.\\-]";

    private FactMatcher() {
    }

    /**
     * Unicode NFKC ({@code ℃} → {@code °C}; the ordinal sign {@code º} models use as a degree sign is read as {@code °}), lower case, markdown bold removed, dashes unified, the space Document
     * Intelligence inserts after a thousands comma ({@code 4, 149}) removed, whitespace collapsed.
     * Only a three-digit group is joined, so {@code April 7, 2025} keeps its space.
     */
    static String normalise(final String text) {
        String s = Normalizer.normalize(text.replace('\u00ba', '\u00b0'), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace("*", "");
        s = DASHES.matcher(s).replaceAll("-");
        s = SPACED_THOUSANDS.matcher(s).replaceAll("$1,$2");
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    /** Where a match sits in a normalised text: {@code start} inclusive, {@code end} exclusive. */
    record Span(int start, int end) {
    }

    /** Every place a normalised text states the fact, in one of its written forms or an accepted spelling. */
    static List<Span> spans(final String normalised, final CorpusFact fact, final List<String> accept) {
        final List<Pattern> patterns = new ArrayList<>(valuePatterns(fact));
        accept.forEach(a -> patterns.add(literal(a)));
        return spans(normalised, patterns);
    }

    static List<Span> spans(final String normalised, final List<Pattern> patterns) {
        final List<Span> spans = new ArrayList<>();
        patterns.forEach(p -> p.matcher(normalised).results().forEach(r -> spans.add(new Span(r.start(), r.end()))));
        return spans;
    }

    /** Matches the normalised text literally. */
    static Pattern literal(final String text) {
        return Pattern.compile(Pattern.quote(normalise(text)));
    }

    /**
     * Key-word groups: every group must have one of its words in the text, in any order. A word must start
     * a word in the text, but may end inside one ({@code seal} matches {@code sealed}, {@code 2 month} does not
     * match {@code 12 months}). Hyphens read as spaces on both sides, so {@code two month} also matches
     * {@code two-month}. The span runs from the first matched word to the last.
     */
    static Optional<Span> keywords(final String normalised, final List<List<String>> groups) {
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        final String text = normalised.replace('-', ' ');
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (final List<String> group : groups) {
            final Optional<Span> word = group.stream()
                    .map(w -> normalise(w).replace('-', ' '))
                    .filter(w -> !w.isEmpty())
                    .flatMap(w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w)).matcher(text).results()
                            .map(r -> new Span(r.start(), r.end())).limit(1))
                    .min(Comparator.comparingInt(Span::start));
            if (word.isEmpty()) {
                return Optional.empty();
            }
            start = Math.min(start, word.get().start());
            end = Math.max(end, word.get().end());
        }
        return Optional.of(new Span(start, end));
    }

    private static List<Pattern> valuePatterns(final CorpusFact fact) {
        return switch (fact.type()) {
            case DATE -> datePatterns(LocalDate.parse(fact.value()));
            case MONEY -> List.of(moneyPattern(new BigDecimal(fact.value())));
            case TEMPERATURE -> List.of(temperaturePattern(new BigDecimal(fact.value())));
            case TEXT -> List.of(literal(fact.value()));
        };
    }

    private static List<Pattern> datePatterns(final LocalDate date) {
        final int day = date.getDayOfMonth();
        final int month = date.getMonthValue();
        final String fullName = date.getMonth().name().toLowerCase(Locale.ROOT);
        final String names = "(?:" + fullName + "|" + fullName.substring(0, 3) + (date.getMonth() == Month.SEPTEMBER ? "|sept" : "") + ")";
        final String dayOfMonth = "0?" + day + "(?:st|nd|rd|th)?";
        final String year = "(?:" + date.getYear() + "|'?" + String.format("%02d", date.getYear() % 100) + ")";

        final List<Pattern> patterns = new ArrayList<>();
        patterns.add(Pattern.compile("\\b0?" + day + DATE_SEPARATOR + "0?" + month + DATE_SEPARATOR + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + dayOfMonth + " (?:of )?" + names + ",? ?" + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + names + " " + dayOfMonth + ",? ?" + year + "\\b"));
        patterns.add(Pattern.compile("\\b0?" + day + DATE_SEPARATOR + names + DATE_SEPARATOR + year + "\\b"));
        patterns.add(Pattern.compile("\\b" + date.getYear() + "(" + DATE_SEPARATOR + ")0?" + month + "\\1" + "0?" + day + "\\b"));
        return patterns;
    }

    /**
     * {@code 9.4°C}, {@code 9.4 °C}, {@code 9.4℃}, {@code 9.4C} and {@code 9.4 degrees}; a whole value may drop its
     * {@code .0} ({@code -18°C}), and a negative one may be written {@code minus 18°C}. A sign must match, so
     * {@code 5.0°C} is not found in {@code -5.0°C}, {@code minus 5.0°C} or {@code 15.0°C}.
     */
    private static Pattern temperaturePattern(final BigDecimal degrees) {
        final BigDecimal whole = degrees.setScale(0, RoundingMode.DOWN);
        final String fraction = degrees.compareTo(whole) == 0
                ? "(?:\\.0+)?"
                : "\\." + degrees.subtract(whole).abs().stripTrailingZeros().toPlainString().substring(2) + "0*";
        final String number = (degrees.signum() < 0 ? "(?:-|\\bminus )" : "(?<![-\\d.])(?<!minus )") + whole.abs().toPlainString() + fraction;
        return Pattern.compile(number + "(?: ?°c| ?degrees?\\b|c\\b)");
    }

    /**
     * {@code £4,149}, {@code £4149}, {@code £ 4,149} and {@code £4,149.00}, but not {@code £41,490} or {@code £4,149.50};
     * a following comma or full stop is punctuation.
     */
    private static Pattern moneyPattern(final BigDecimal amount) {
        final BigDecimal whole = amount.setScale(0, RoundingMode.DOWN);
        final String grouped = String.format(Locale.UK, "%,d", whole.longValueExact()).replace(",", ",?");
        final boolean hasPence = amount.compareTo(whole) != 0;
        final String pence = hasPence
                ? "\\." + amount.subtract(whole).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString()
                : "(?:\\.00)?";
        return Pattern.compile("£ ?" + grouped + pence + "(?!\\d|,\\d|\\.\\d)");
    }
}
