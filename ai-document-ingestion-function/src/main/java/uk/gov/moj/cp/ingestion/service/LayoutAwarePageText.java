package uk.gov.moj.cp.ingestion.service;

import java.util.List;

import com.azure.ai.documentintelligence.models.DocumentLine;

/**
 * Joins a page's Document Intelligence lines using their geometry: {@code \t} between a line and the
 * next line on the same row to its right, {@code \n} otherwise (DD-43810).
 */
final class LayoutAwarePageText {

    private LayoutAwarePageText() {
    }

    static String join(final List<DocumentLine> lines) {
        throw new UnsupportedOperationException("DD-43810: not implemented");
    }
}
