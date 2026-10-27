package uk.gov.moj.cp.orchestrator.eval;

/** A reference to a manifest fact, written {@code "<file>#<fact-id>"} in the dataset. */
public record FactRef(String file, String factId) {

    static FactRef parse(final String ref) {
        final int hash = ref.indexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            throw new IllegalStateException("Fact reference must be <file>#<fact-id>: " + ref);
        }
        return new FactRef(ref.substring(0, hash), ref.substring(hash + 1));
    }

    @Override
    public String toString() {
        return file + "#" + factId;
    }
}
