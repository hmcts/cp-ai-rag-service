package uk.gov.moj.cp.migration;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.index.IndexConstants.ID;
import static uk.gov.moj.cp.ai.util.StringUtil.escapeODataStringLiteral;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A contiguous slice of the key space — {@code [lower, upper)}, where a {@code null} bound is unbounded —
 * with an optional resume cursor. Owns the partitioning of the (UUID) key space into shards and the OData
 * range/keyset filter construction for a page.
 */
record Shard(String lower, String upper, String startCursor) {

    private static final Logger LOGGER = LoggerFactory.getLogger(Shard.class);

    /** Ordered hex prefixes of a v4 UUID key — the leading nibble is uniform, giving 16 even shards. */
    private static final String HEX = "0123456789abcdef";

    /**
     * Partitions the key space. One worker → a single full-range shard seeded with the resume cursor.
     * More workers → 16 shards by the leading hex character of the UUID key, each starting fresh
     * ({@code startAfterId} is ignored — per-shard cursors make a single resume id meaningless, and
     * re-running is safe because uploads are idempotent upserts).
     */
    static List<Shard> plan(final int workers, final String startAfterId) {
        if (workers <= 1) {
            return List.of(new Shard(null, null, startAfterId));
        }
        if (startAfterId != null && !startAfterId.isEmpty()) {
            LOGGER.warn("startAfterId is ignored when workers > 1; shards restart from their range "
                    + "(re-run is safe — uploads are idempotent upserts).");
        }
        final List<Shard> shards = new ArrayList<>(HEX.length());
        for (int i = 0; i < HEX.length(); i++) {
            final String lower = String.valueOf(HEX.charAt(i));
            final String upper = i < HEX.length() - 1 ? String.valueOf(HEX.charAt(i + 1)) : "g";
            shards.add(new Shard(lower, upper, null));
        }
        return shards;
    }

    /** Short label for logging: the lower bound, or "all" for the unbounded single shard. */
    String label() {
        return lower == null ? "all" : lower;
    }

    /** Composes the OData page filter: this shard's bounds (if any) AND the keyset cursor (if any). */
    String pageFilter(final String lastId) {
        final List<String> parts = new ArrayList<>(3);
        if (lower != null) {
            parts.add(format("%s ge '%s'", ID, escapeODataStringLiteral(lower)));
        }
        if (upper != null) {
            parts.add(format("%s lt '%s'", ID, escapeODataStringLiteral(upper)));
        }
        final String cursor = keysetFilter(lastId);
        if (cursor != null) {
            parts.add(cursor);
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    /** The keyset cursor predicate ({@code id gt '<lastId>'}), or {@code null} for the first page. */
    static String keysetFilter(final String lastId) {
        if (lastId == null || lastId.isEmpty()) {
            return null;
        }
        return format("%s gt '%s'", ID, escapeODataStringLiteral(lastId));
    }
}
