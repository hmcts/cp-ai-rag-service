package uk.gov.moj.cp.retrieval.service;

import static org.xmlunit.assertj3.XmlAssert.assertThat;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkFormatterServiceTest {

    private final ChunkFormatterService chunkFormatterService = new ChunkFormatterService();

    @Test
    void buildChunkContext_ReturnsEmptyTag_WhenEntriesNullOrEmpty() {
        String resultNull = chunkFormatterService.buildChunkContext(null);
        String resultEmpty = chunkFormatterService.buildChunkContext(List.of());
        assertThat(resultNull).hasXPath("/RETRIEVED_DOCUMENTS");
        assertThat(resultNull).doesNotHaveXPath("/RETRIEVED_DOCUMENTS/DOCUMENT");
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
        String result = chunkFormatterService.buildChunkContext(List.of(entry));
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1' and @DOCUMENT_FILENAME='MaterialX']/DATA[1]/DOCUMENT_CONTENT[text()='Some content']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1' and @DOCUMENT_FILENAME='MaterialX']/DATA[1]/PAGE_NUMBER[text()='5']");
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
        String result = chunkFormatterService.buildChunkContext(List.of(entry));
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
        String result = chunkFormatterService.buildChunkContext(List.of(entry1, entry2, entry3));
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1']/DATA[1]/DOCUMENT_CONTENT[text()='Content1']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc1']/DATA[2]/DOCUMENT_CONTENT[text()='Content2']");
        assertThat(result).hasXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc2']/DATA[1]/DOCUMENT_CONTENT[text()='Content3']");
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
        String result = chunkFormatterService.buildChunkContext(List.of(entry));
        assertThat(result).valueByXPath("/RETRIEVED_DOCUMENTS/DOCUMENT[@DOCUMENT_ID='doc3']/DATA[1]/PAGE_NUMBER").isEmpty();
    }
}

