package uk.gov.moj.cp.metadata.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobProperties;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobMetadataValidationTest {

    private BlobMetadataValidationService blobMetadataValidationService;
    private BlobClientService blobClientServiceMock;

    @BeforeEach
    void setUp() {
        blobClientServiceMock = mock(BlobClientService.class);
        blobMetadataValidationService = new BlobMetadataValidationService(blobClientServiceMock);
    }

    @Test
    @DisplayName("Extracts metadata successfully from existing blob")
    void shouldExtractMetadataFromExistingBlob() throws Exception {
        // Given
        String blobName = "test-document.pdf";
        BlobClient blobClientMock = mock(BlobClient.class);
        BlobProperties blobPropertiesMock = mock(BlobProperties.class);
        
        Map<String, String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("document_id", "123e4567-e89b-12d3-a456-426614174000");
        expectedMetadata.put("case_id", "CASE-12345");

        //when
        when(blobClientServiceMock.getBlobClient(blobName)).thenReturn(blobClientMock);
        when(blobClientMock.exists()).thenReturn(true);
        when(blobClientMock.getProperties()).thenReturn(blobPropertiesMock);
        when(blobPropertiesMock.getMetadata()).thenReturn(expectedMetadata);

        // Then
        Map<String, String> result = blobMetadataValidationService.extractBlobMetadata(blobName);

        // Assert
        assertNotNull(result);
        assertEquals(expectedMetadata, result);
    }

    @Test
    @DisplayName("Returns empty metadata when blob does not exist")
    void shouldReturnEmptyMetadataWhenBlobDoesNotExist() throws Exception {
        // Given
        String blobName = "Empty.pdf";
        BlobClient blobClientMock = mock(BlobClient.class);
        // When
        when(blobClientServiceMock.getBlobClient(blobName)).thenReturn(blobClientMock);
        when(blobClientMock.exists()).thenReturn(false);

        //Then
        Map<String, String> result = blobMetadataValidationService.extractBlobMetadata(blobName);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
