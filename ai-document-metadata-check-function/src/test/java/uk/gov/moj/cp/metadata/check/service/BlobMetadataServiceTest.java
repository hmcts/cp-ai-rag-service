package uk.gov.moj.cp.metadata.check.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cp.metadata.check.exception.MetadataValidationException;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlobMetadataServiceTest {

    @Mock
    private BlobClientFactory blobClientFactory;

    @Mock
    private OutcomeStorageService outcomeStorageService;

    @Mock
    private BlobClient blobClient;

    @Mock
    private BlobProperties blobProperties;

    private BlobMetadataService blobMetadataService;

    @BeforeEach
    void setUp() {
        blobMetadataService = new BlobMetadataService(blobClientFactory, outcomeStorageService);
    }

    @Test
    @DisplayName("Process Blob Metadata Successfully with Valid Data")
    void shouldProcessBlobMetadataSuccessfully() {
        // given
        String documentName = "test.pdf";
        Map<String, String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        expectedMetadata.put("content_type", "application/pdf");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(expectedMetadata);

        // when
        Map<String, String> result = blobMetadataService.processBlobMetadata(documentName);

        // then
        assertNotNull(result);
        assertEquals(expectedMetadata, result);
        assertEquals("123e4567-e89b-12d3-a456-426614174000", result.get("document_id"));
        assertEquals("application/pdf", result.get("content_type"));
        verify(blobClientFactory).getBlobClient(documentName);
        verify(blobClient).exists();
        verify(blobClient).getProperties();
        verify(outcomeStorageService, never()).store(any());
    }

    @Test
    @DisplayName("Throw Exception When Blob Does Not Exist")
    void shouldThrowExceptionWhenBlobNotFound() {
        // given
        String documentName = "nonexistent.pdf";

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> {
            blobMetadataService.processBlobMetadata(documentName);
        });

        // The actual implementation catches the specific exception and wraps it
        assertTrue(exception.getMessage().contains("Failed to extract metadata for blob: " + documentName));
        verify(blobClientFactory).getBlobClient(documentName);
        verify(blobClient).exists();
        verify(outcomeStorageService).store(any());
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Missing")
    void shouldThrowExceptionWhenDocumentIdMissing() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("content_type", "application/pdf");
        // Missing document_id

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> {
            blobMetadataService.processBlobMetadata(documentName);
        });

        // The actual implementation catches the specific exception and wraps it
        assertTrue(exception.getMessage().contains("Failed to extract metadata for blob: " + documentName));
        verify(outcomeStorageService).store(any());
    }

    @Test
    @DisplayName("Throw Exception When Document ID is Blank")
    void shouldThrowExceptionWhenDocumentIdBlank() {
        // given
        String documentName = "test.pdf";
        Map<String, String> metadata = new HashMap<>();
        metadata.put("document_id", "   "); // Blank document_id
        metadata.put("content_type", "application/pdf");

        when(blobClientFactory.getBlobClient(documentName)).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(metadata);

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> {
            blobMetadataService.processBlobMetadata(documentName);
        });

        // The actual implementation catches the specific exception and wraps it
        assertTrue(exception.getMessage().contains("Failed to extract metadata for blob: " + documentName));
        verify(outcomeStorageService).store(any());
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
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> {
            blobMetadataService.processBlobMetadata(documentName);
        });

        // The actual implementation catches the specific exception and wraps it
        assertTrue(exception.getMessage().contains("Failed to extract metadata for blob: " + documentName));
        verify(outcomeStorageService).store(any());
    }

    @Test
    @DisplayName("Handle General Exception During Processing")
    void shouldHandleGeneralException() {
        // given
        String documentName = "test.pdf";

        when(blobClientFactory.getBlobClient(documentName)).thenThrow(new RuntimeException("Connection failed"));

        // when & then
        MetadataValidationException exception = assertThrows(MetadataValidationException.class, () -> {
            blobMetadataService.processBlobMetadata(documentName);
        });

        assertEquals("Failed to extract metadata for blob: " + documentName, exception.getMessage());
    }
}
