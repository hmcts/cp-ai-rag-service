package uk.gov.moj.cp.ai.storage;

import static java.lang.String.format;
import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

/**
 * The blob-storage namespace convention for client-scoped blobs: every blob belonging to a client
 * lives under a virtual directory of the form {@code c={clientId}/}, e.g.
 * {@code c=8f7b.../doc123_20260722.pdf}. Blobs written before client scoping existed keep their
 * flat legacy names in the same containers.
 *
 * <p>A {@code key=} style marker is used rather than a bare {@code {clientId}/} directory because:
 * <ul>
 *   <li><b>Unambiguous dual-shape parsing.</b> Containers hold both legacy flat names and
 *       client-prefixed names during the transition. A name starting with the marker is
 *       definitively client-prefixed (and is rejected if it does not parse as exactly that),
 *       while a flat name can never be mistaken for one. An anonymous leading directory would
 *       make the two shapes indistinguishable by inspection.</li>
 *   <li><b>Self-describing paths.</b> In storage browsers, logs and SAS URLs the segment
 *       announces what it is — the same idiom as data-lake partition paths such as
 *       {@code date=2026-07-22/}.</li>
 *   <li><b>Composable namespaces.</b> Additional keyed segments can be introduced later without
 *       ambiguity, which stacked anonymous directories would not allow.</li>
 *   <li><b>Precise prefix operations.</b> Per-client lifecycle rules, listings and SAS scopes
 *       filter on {@code c={clientId}/}, which cannot collide with a document name that merely
 *       starts with the same characters.</li>
 * </ul>
 *
 * <p>The blob path is a convenience namespace only — the Table Storage row remains the
 * authoritative record of which client owns a document.
 */
public final class BlobNamespace {

    /** Marker announcing a client-namespace segment at the start of a blob name. */
    public static final String CLIENT_PREFIX_MARKER = "c=";

    private BlobNamespace() {
        // Utility class
    }

    /**
     * Places a blob name under the client's namespace when a clientId is supplied; returns the
     * name unchanged when it is null/blank (legacy flat shape).
     */
    public static String applyClientPrefix(final String clientId, final String blobName) {
        return isNullOrEmpty(clientId) ? blobName : format("%s%s/%s", CLIENT_PREFIX_MARKER, clientId, blobName);
    }
}
