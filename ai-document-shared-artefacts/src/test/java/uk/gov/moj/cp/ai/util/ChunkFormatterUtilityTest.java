package uk.gov.moj.cp.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.xmlunit.assertj3.XmlAssert.assertThat;
import static uk.gov.moj.cp.ai.util.ChunkFormatterUtility.MATERIAL_NAME_METADATA_KEY;
import static uk.gov.moj.cp.ai.util.ChunkFormatterUtility.displayedFileName;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkFormatterUtilityTest {

    private final ChunkFormatterUtility chunkFormatterUtility = new ChunkFormatterUtility();

    @Test
    void buildChunkContext_ReturnsEmptyTag_WhenEntriesNullOrEmpty() {
        final String resultNull = chunkFormatterUtility.buildChunkContext(null);
        assertThat(resultNull).hasXPath("/RETRIEVED_DOCUMENTS");
        assertThat(resultNull).doesNotHaveXPath("/RETRIEVED_DOCUMENTS/DOCUMENT");

        final String resultEmpty = chunkFormatterUtility.buildChunkContext(List.of());
        assertThat(resultEmpty).hasXPath("/RETRIEVED_DOCUMENTS");
        assertThat(resultEmpty).doesNotHaveXPath("/RETRIEVED_DOCUMENTS/DOCUMENT");
    }

    @Test
    void buildChunkContext_FormatsSingleEntryCorrectly() {
        ChunkedEntry entry = ChunkedEntry.builder()
                .id("id1")
                .documentId("doc1")
                .chunk("Some content")
                .documentFileName("file1.pdf")
                .pageNumber(5)
                .customMetadata(List.of(new KeyValuePair("material_name", "MaterialX")))
                .build();
        String result = chunkFormatterUtility.buildChunkContext(List.of(entry));
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1' and @DOCUMENT_FILENAME='MaterialX']/DATA[1]/DOCUMENT_CONTENT[text()='Some content']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1' and @DOCUMENT_FILENAME='MaterialX']/DATA[1]/PAGE_NUMBER[text()='5']");
        assertThat(result).doesNotHaveXPath("/RETRIEVED_DOCUMENTS/DOCUMENT/DATA/@CHUNK_ID");
    }

    @Test
    void buildChunkContext_UsesDocumentFileNameIfNoMaterialName() {
        ChunkedEntry entry = ChunkedEntry.builder()
                .id("id2")
                .documentId("doc2")
                .chunk("Other content")
                .documentFileName("file2.pdf")
                .pageNumber(2)
                .customMetadata(List.of(new KeyValuePair("other_key", "value")))
                .build();
        String result = chunkFormatterUtility.buildChunkContext(List.of(entry));
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc2' and @DOCUMENT_FILENAME='file2.pdf']/DATA[1]/DOCUMENT_CONTENT[text()='Other content']");

    }

    @Test
    void buildChunkContext_FormatsMultipleEntriesAndDocuments() {
        ChunkedEntry entry1 = ChunkedEntry.builder()
                .id("id1")
                .documentId("doc1")
                .chunk("Content1")
                .documentFileName("file1.pdf")
                .pageNumber(1)
                .customMetadata(List.of(new KeyValuePair("material_name", "Mat1")))
                .build();
        ChunkedEntry entry2 = ChunkedEntry.builder()
                .id("id2")
                .documentId("doc1")
                .chunk("Content2")
                .documentFileName("file1.pdf")
                .pageNumber(2)
                .customMetadata(List.of(new KeyValuePair("material_name", "Mat1")))
                .build();
        ChunkedEntry entry3 = ChunkedEntry.builder()
                .id("id3")
                .documentId("doc2")
                .chunk("Content3")
                .documentFileName("file2.pdf")
                .pageNumber(3)
                .customMetadata(List.of(new KeyValuePair("material_name", "Mat2")))
                .build();
        String result = chunkFormatterUtility.buildChunkContext(List.of(entry1, entry2, entry3));
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_FILENAME='Mat1' and @DOCUMENT_ID='doc1']/DATA[1]/DOCUMENT_CONTENT[text()='Content1']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_FILENAME='Mat1' and @DOCUMENT_ID='doc1']/DATA[2]/DOCUMENT_CONTENT[text()='Content2']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_FILENAME='Mat2' and @DOCUMENT_ID='doc2']/DATA[1]/DOCUMENT_CONTENT[text()='Content3']");
    }

    @Test
    void buildChunkContext_HandlesNullPageNumber() {
        ChunkedEntry entry = ChunkedEntry.builder()
                .id("id4")
                .documentId("doc3")
                .chunk("No page number")
                .documentFileName("file3.pdf")
                .customMetadata(List.of())
                .build();
        String result = chunkFormatterUtility.buildChunkContext(List.of(entry));
        assertThat(result).valueByXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc3']/DATA[1]/PAGE_NUMBER").isEmpty();
    }

    @Test
    void displayedFileName_ReturnsMaterialName_WhenPresentAndNonBlank() {
        final ChunkedEntry entry = ChunkedEntry.builder()
                .documentFileName("report-a.pdf")
                .customMetadata(List.of(new KeyValuePair("other_key", "value"),
                        new KeyValuePair(MATERIAL_NAME_METADATA_KEY, "summary-a")))
                .build();
        assertEquals("summary-a", displayedFileName(entry));
    }

    @Test
    void displayedFileName_FallsBackToDocumentFileName_WhenMaterialNameBlank() {
        final ChunkedEntry entry = ChunkedEntry.builder()
                .documentFileName("report-a.pdf")
                .customMetadata(List.of(new KeyValuePair(MATERIAL_NAME_METADATA_KEY, "")))
                .build();
        assertEquals("report-a.pdf", displayedFileName(entry));
    }

    @Test
    void displayedFileName_FallsBackToDocumentFileName_WhenMaterialNameAbsent() {
        final ChunkedEntry withOtherMetadata = ChunkedEntry.builder()
                .documentFileName("report-a.pdf")
                .customMetadata(List.of(new KeyValuePair("other_key", "value")))
                .build();
        final ChunkedEntry withNullMetadata = ChunkedEntry.builder()
                .documentFileName("notes-b.pdf")
                .build();
        assertEquals("report-a.pdf", displayedFileName(withOtherMetadata));
        assertEquals("notes-b.pdf", displayedFileName(withNullMetadata));
    }

    @Test
    void displayedFileName_ReturnsNull_WhenNoMaterialNameAndNoDocumentFileName() {
        final ChunkedEntry entry = ChunkedEntry.builder()
                .customMetadata(List.of())
                .build();
        assertNull(displayedFileName(entry));
    }
}
