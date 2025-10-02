package uk.gov.moj.cp.metadata.check.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import uk.gov.moj.cp.ai.service.TableStorageService;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentMetadataServiceTest {

    @Mock
    private BlobClientFactory blobClientFactory;

    @Mock
    private TableStorageService tableStorageService;

    @Mock
    private BlobClient blobClient;

    @Mock
    private BlobProperties blobProperties;

    private DocumentMetadataService documentMetadataService;

    @BeforeEach
    void setUp() {
        documentMetadataService = new DocumentMetadataService(blobClientFactory, tableStorageService);
    }

    @Test
    @DisplayName("Process Blob Metadata Successfully with Valid Data")
    void shouldProcessDocumentMetadataSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        expectedMetadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
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
        verify(blobClientFactory).getBlobClient(documentName);
        verify(blobClient).exists();
        verify(blobClient).getProperties();
        verify(tableStorageService, never()).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Blob Does Not Exist")
    void shouldThrowExceptionWhenBlobNotFound() {
        // given
        String documentName = "nonexistent.pdf";

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertEquals("Blob not found: " + documentName, exception.getMessage());
        verify(blobClientFactory).getBlobClient(documentName);
        verify(blobClient).exists();
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Missing")
    void shouldThrowExceptionWhenDocumentIdMissing() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");
        // Missing document_id

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Missing document ID: " + documentName));
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Blank")
    void shouldThrowExceptionWhenDocumentIdBlank() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "   "); // Blank document_id
        metadata.put("metadata", "{\"case_id\":\"b99704aa-b1b1-4d5f-bb39-47dc3f18ffa9\",\"document_type\":\"MCC\"}");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Missing document ID: " + documentName));
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Invalid UUID")
    void shouldThrowExceptionWhenDocumentIdInvalid() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "invalid-uuid");
        metadata.put("content_type", "application/pdf");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Invalid UUID string: invalid-uuid"));
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Nested Metadata is Invalid JSON")
    void shouldThrowExceptionWhenNestedMetadataInvalid() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("metadata", "invalid-json");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Unrecognized token 'invalid'"));
        verify(tableStorageService).recordOutcome(any());
    }

    @Test
    @DisplayName("Throw Exception When Nested Metadata Has Blank Values")
    void shouldThrowExceptionWhenNestedMetadataHasBlankValues() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        metadata.put("metadata", "{\"case_id\":\"\",\"document_type\":\"MCC\"}");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class,
                () -> documentMetadataService.processDocumentMetadata(documentName));

        assertTrue(exception.getMessage().contains("Invalid nested metadata key/value: 'case_id' in blob " + documentName));
        verify(tableStorageService, times(1)).recordOutcome(any());
    }

    @Test
    @DisplayName("Handle General Exception During Processing")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";

        when(blobClientFactory.getBlobClient(documentName)).thenThrow(new RuntimeException("Connection failed"));

        // when & then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> documentMetadataService.processDocumentMetadata(documentName));

        assertEquals("Connection failed", exception.getMessage());
    }
}
