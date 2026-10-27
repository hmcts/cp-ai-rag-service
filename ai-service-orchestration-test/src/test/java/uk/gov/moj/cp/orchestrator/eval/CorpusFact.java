package uk.gov.moj.cp.orchestrator.eval;

import java.util.List;
import java.util.Locale;

/**
 * A fact planted in a corpus document, as listed in {@code corpus-manifest.json}: its type, its
 * value as written in the manifest (ISO date, plain amount or literal text) and every page it
 * appears on.
 */
public record CorpusFact(FactType type, String value, List<Integer> pages) {

    public enum FactType {
        DATE, MONEY, NUMBER, TEXT;

        static FactType of(final String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }
}
