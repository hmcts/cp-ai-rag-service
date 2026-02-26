package uk.gov.moj.cp.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobClientServiceTest {

    @Test
    @DisplayName("Throws exception when container name is null or empty")
    void getInstanceThrowsExceptionWhenContainerNameIsNullOrEmpty() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new BlobClientService((String) null));
        assertEquals("Container name cannot be null or empty.", exception.getMessage());

        exception = assertThrows(IllegalArgumentException.class,
                () -> new BlobClientService(""));
        assertEquals("Container name cannot be null or empty.", exception.getMessage());
    }

    @Test
    @DisplayName("Returns BlobClient for valid document name")
    void getBlobClientReturnsBlobClientForValidDocumentName() {
        BlobClient blobClient = mock(BlobClient.class);
        BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        when(containerClientMock.getBlobClient("documentName")).thenReturn(blobClient);

        BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        BlobClient result = service.getBlobClient("documentName");

        assertNotNull(result);
        assertEquals(blobClient, result);
    }

    @Test
    @DisplayName("Uploads blob successfully")
    void addBlobUploadsBlobSuccessfully() {
        BlobClient blobClient = mock(BlobClient.class);
        BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        when(containerClientMock.getBlobClient("documentName")).thenReturn(blobClient);
        when(containerClientMock.getBlobContainerName()).thenReturn("containerName");
        BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);

        BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        service.addBlob("documentName", "payload");

        verify(blobClient).upload(any(java.io.ByteArrayInputStream.class), eq(Long.valueOf("payload".getBytes(StandardCharsets.UTF_8).length)), eq(true));
    }

    @Test
    @DisplayName("Get SAS Storage URL")
    void getSASUrlForGivenBlobName() {
        BlobClient blobClient = mock(BlobClient.class);
        BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final UserDelegationKey UserDelegationKeyMock = mock(UserDelegationKey.class);

        when(serviceClientMock.getUserDelegationKey(any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(UserDelegationKeyMock);
        when(containerClientMock.getBlobClient("blobName")).thenReturn(blobClient);

        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/blobName.pdf");
        when(blobClient.generateUserDelegationSas(any(BlobServiceSasSignatureValues.class), eq(UserDelegationKeyMock)))
                .thenReturn("sv=2025-11-05&st=2026-02-19T15%3A40%3A57Z&se=2026-02-19T17%3A40%3A57Z");

        BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        final String sasUrl = service.getSasUrl("blobName", 120);

        assertThat(sasUrl).isEqualTo("https://account.blob.core.windows.net/documents/blobName.pdf?sv=2025-11-05&st=2026-02-19T15%3A40%3A57Z&se=2026-02-19T17%3A40%3A57Z");
    }
}
