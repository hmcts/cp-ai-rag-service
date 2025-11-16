package uk.gov.moj.cp.metadata.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import java.util.HashMap;
import java.util.Map;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceTest {

    @Mock
    private BlobClientService blobClientService;

    @Mock
    private BlobClient blobClient;

    @Mock
    private BlobProperties blobProperties;

    private DocumentMetadataService documentMetadataService;

    @BeforeEach
    void setUp() {
        documentMetadataService = new DocumentMetadataService(blobClientService);
    }

    @Test
    @DisplayName("Process Blob Metadata Successfully with Valid Data")
    void shouldProcessDocumentMetadataSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        expectedMetadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(expectedMetadata);

        // when
        Map<String, String> result = documentMetadataService.processDocumentMetadata(documentName);

        // then
        assertNotNull(result);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.get("document_id"));
        assertEquals("b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9", result.get("case_id"));
        assertEquals("MCC", result.get("document_type"));
        // The nested metadata should be flattened and the original "metadata" key should be removed
        assertNull(result.get("metadata"));
        verify(blobClientService).getBlobClient(documentName);
        verify(blobClient).exists();
        verify(blobClient).getProperties();
    }

    @Test
    @DisplayName("Throw Exception When Blob Does Not Exist")
    void shouldThrowExceptionWhenBlobNotFound() {
        // given
        String documentName = "nonexistent.pdf";

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertEquals("Blob not found: " + documentName, exception.getMessage());
        verify(blobClientService).getBlobClient(documentName);
        verify(blobClient).exists();
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Missing")
    void shouldThrowExceptionWhenDocumentIdMissing() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");
        // Missing document_id

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Missing document ID: " + documentName));
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Blank")
    void shouldThrowExceptionWhenDocumentIdBlank() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "   "); // Blank document_id
        metadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Missing document ID: " + documentName));
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Invalid UUID")
    void shouldThrowExceptionWhenDocumentIdInvalid() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "invalid-uuid");
        metadata.put("content_type", "application/pdf");

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Invalid UUID string: invalid-uuid"));
    }

    @Test
    @DisplayName("Throw Exception When Nested Metadata is Invalid JSON")
    void shouldThrowExceptionWhenNestedMetadataInvalid() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("metadata", "invalid-json");

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Unrecognized token 'invalid'"));
    }

    @Test
    @DisplayName("Throw Exception When Nested Metadata Has Blank Values")
    void shouldThrowExceptionWhenNestedMetadataHasBlankValues() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("metadata", "{\"case_id\":\"\",\"document_type\":\"MCC\"}");

        when(blobClientService.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Invalid nested metadata key/value: 'case_id' in blob " + documentName));
    }

    @Test
    @DisplayName("Handle General Exception During Processing")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";

        when(blobClientService.getBlobClient(documentName)).thenThrow(new RuntimeException("Connection failed"));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertEquals("Connection failed", exception.getMessage());
    }

}
