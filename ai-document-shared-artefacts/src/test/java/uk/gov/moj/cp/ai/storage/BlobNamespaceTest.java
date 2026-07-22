package uk.gov.moj.cp.ai.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Specs for the client blob-namespace helper: a supplied clientId places the name under the
 * client's virtual directory; a null/blank clientId leaves the flat legacy name untouched.
 */
class BlobNamespaceTest {

    @Test
    @DisplayName("applyClientPrefix places the name under the client's namespace when a clientId is supplied")
    void shouldPrefix_whenClientIdSupplied() {
        assertEquals("c=client-1/doc_20260722.pdf", BlobNamespace.applyClientPrefix("client-1", "doc_20260722.pdf"));
    }

    @Test
    @DisplayName("applyClientPrefix leaves the name unchanged when clientId is null")
    void shouldNotPrefix_whenClientIdNull() {
        assertEquals("doc_20260722.pdf", BlobNamespace.applyClientPrefix(null, "doc_20260722.pdf"));
    }

    @Test
    @DisplayName("applyClientPrefix leaves the name unchanged when clientId is blank")
    void shouldNotPrefix_whenClientIdBlank() {
        assertEquals("doc_20260722.pdf", BlobNamespace.applyClientPrefix(" ", "doc_20260722.pdf"));
    }
}
