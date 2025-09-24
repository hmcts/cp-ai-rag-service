package uk.gov.moj.cp.metadata.check.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobUtilTest {

    @Test
    @DisplayName("Should return true for valid metadata with correct UUID format")
    void shouldReturnTrueForValidMetadataWithCorrectUuidFormat() {
        // given
        Map<String, String> validMetadata = new HashMap<>();
        validMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        validMetadata.put("case_id", "CASE-12345");

        // when
        boolean result = BlobUtil.isValidMetadata(validMetadata);

        // then
        assertTrue(result);
    }

    @Test
    @DisplayName("Should return false for metadata with null document_id")
    void shouldReturnFalseForMetadataWithNullDocumentId() {
        // given
        Map<String, String> invalidMetadata = new HashMap<>();
        invalidMetadata.put("case_id", "CASE-12345");
        // document_id is missing

        // when
        boolean result = BlobUtil.isValidMetadata(invalidMetadata);

        // then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false for metadata with empty document_id")
    void shouldReturnFalseForMetadataWithEmptyDocumentId() {
        // given
        Map<String, String> invalidMetadata = new HashMap<>();
        invalidMetadata.put("document_id", "");
        invalidMetadata.put("case_id", "CASE-12345");

        // when
        boolean result = BlobUtil.isValidMetadata(invalidMetadata);

        // then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false for metadata with whitespace-only document_id")
    void shouldReturnFalseForMetadataWithWhitespaceOnlyDocumentId() {
        // given
        Map<String, String> invalidMetadata = new HashMap<>();
        invalidMetadata.put("document_id", "   ");
        invalidMetadata.put("case_id", "CASE-12345");

        // when
        boolean result = BlobUtil.isValidMetadata(invalidMetadata);

        // then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false for metadata with invalid UUID format")
    void shouldReturnFalseForMetadataWithInvalidUuidFormat() {
        // given
        Map<String, String> invalidMetadata = new HashMap<>();
        invalidMetadata.put("document_id", "invalid-uuid-format");
        invalidMetadata.put("case_id", "CASE-12345");

        // when
        boolean result = BlobUtil.isValidMetadata(invalidMetadata);

        // then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should create queue message with all required fields")
    void shouldCreateQueueMessageWithAllRequiredFields() {
        // given
        String blobName = "test-document.pdf";
        String storageAccountName = "teststorageaccount";
        String containerName = "testcontainer";
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        blobMetadata.put("case_id", "CASE-12345");
        blobMetadata.put("material_id", "MAT-001");

        // when
        Map<String, Object> result = BlobUtil.createQueueMessage(blobName, blobMetadata, storageAccountName, containerName);

        // then
        assertNotNull(result);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.get("document_id"));
        assertEquals("CASE-12345", result.get("case_id"));
        assertEquals("MAT-001", result.get("material_id"));
        assertEquals("https://teststorageaccount.blob.core.windows.net/testcontainer/test-document.pdf", result.get("blob_url"));
        assertNotNull(result.get("current_timestamp"));
    }

    @Test
    @DisplayName("Should create queue message with minimal metadata")
    void shouldCreateQueueMessageWithMinimalMetadata() {
        // given
        String blobName = "minimal-document.pdf";
        String storageAccountName = "teststorageaccount";
        String containerName = "testcontainer";
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");

        // when
        Map<String, Object> result = BlobUtil.createQueueMessage(blobName, blobMetadata, storageAccountName, containerName);

        // then
        assertNotNull(result);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.get("document_id"));
        assertEquals("https://teststorageaccount.blob.core.windows.net/testcontainer/minimal-document.pdf", result.get("blob_url"));
        assertNotNull(result.get("current_timestamp"));
        assertEquals(3, result.size()); // document_id, blob_url, current_timestamp
    }

    @Test
    @DisplayName("Should not duplicate document_id in queue message")
    void shouldNotDuplicateDocumentIdInQueueMessage() {
        // given
        String blobName = "test-document.pdf";
        String storageAccountName = "teststorageaccount";
        String containerName = "testcontainer";
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        blobMetadata.put("case_id", "CASE-12345");

        // when
        Map<String, Object> result = BlobUtil.createQueueMessage(blobName, blobMetadata, storageAccountName, containerName);

        // then
        assertNotNull(result);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.get("document_id"));
        assertEquals("CASE-12345", result.get("case_id"));
        assertEquals(4, result.size()); // document_id, case_id, blob_url, current_timestamp
        // Ensure document_id appears only once
        long documentIdCount = result.entrySet().stream()
                .filter(entry -> "document_id".equals(entry.getKey()))
                .count();
        assertEquals(1, documentIdCount);
    }

    @Test
    @DisplayName("Should handle blob name with special characters in URL")
    void shouldHandleBlobNameWithSpecialCharactersInUrl() {
        // given
        String blobName = "test document (1).pdf";
        String storageAccountName = "teststorageaccount";
        String containerName = "testcontainer";
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");

        // when
        Map<String, Object> result = BlobUtil.createQueueMessage(blobName, blobMetadata, storageAccountName, containerName);

        // then
        assertNotNull(result);
        assertEquals("https://teststorageaccount.blob.core.windows.net/testcontainer/test document (1).pdf", result.get("blob_url"));
    }
}
