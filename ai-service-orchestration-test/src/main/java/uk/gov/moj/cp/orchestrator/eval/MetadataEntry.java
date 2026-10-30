package uk.gov.moj.cp.orchestrator.eval;

/** One metadata key/value pair: attached to a document at ingestion, or used as a query filter. */
public record MetadataEntry(String key, String value) {
}
