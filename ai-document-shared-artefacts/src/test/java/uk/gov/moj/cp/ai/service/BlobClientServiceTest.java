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
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.CopyStatusType;
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
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        when(containerClientMock.getBlobClient("documentName")).thenReturn(blobClient);

        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        final BlobClient result = service.getBlobClient("documentName");

        assertNotNull(result);
        assertEquals(blobClient, result);
    }

    @Test
    @DisplayName("Uploads blob successfully")
    void addBlobUploadsBlobSuccessfully() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        when(containerClientMock.getBlobClient("documentName")).thenReturn(blobClient);
        when(containerClientMock.getBlobContainerName()).thenReturn("containerName");
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);

        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        service.addBlob("documentName", "payload");

        verify(blobClient).upload(any(java.io.ByteArrayInputStream.class), eq(Long.valueOf("payload".getBytes(StandardCharsets.UTF_8).length)), eq(true));
    }

    @Test
    @DisplayName("Get SAS Storage URL")
    void getSASUrlForGivenBlobName() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final UserDelegationKey UserDelegationKeyMock = mock(UserDelegationKey.class);

        when(serviceClientMock.getUserDelegationKey(any(OffsetDateTime.class), any(OffsetDateTime.class))).thenReturn(UserDelegationKeyMock);
        when(containerClientMock.getBlobClient("blobName")).thenReturn(blobClient);

        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/documents/blobName.pdf");
        when(blobClient.generateUserDelegationSas(any(BlobServiceSasSignatureValues.class), eq(UserDelegationKeyMock)))
                .thenReturn("sv=2025-11-05&st=2026-02-19T15%3A40%3A57Z&se=2026-02-19T17%3A40%3A57Z");

        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);
        final String sasUrl = service.getSasUrl("blobName", 120);

        assertThat(sasUrl).isEqualTo("https://account.blob.core.windows.net/documents/blobName.pdf?sv=2025-11-05&st=2026-02-19T15%3A40%3A57Z&se=2026-02-19T17%3A40%3A57Z");
    }

    @Test
    void shouldReturnTrue_whenCopyStatusIsNull() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobProperties blobProperties = mock(BlobProperties.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);

        when(containerClientMock.getBlobClient("testBlob")).thenReturn(blobClient);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getCopyStatus()).thenReturn(null);

        final boolean result = service.isBlobAvailable("testBlob");

        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnTrue_whenCopyStatusIsSuccess() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobProperties blobProperties = mock(BlobProperties.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);

        when(containerClientMock.getBlobClient("testBlob")).thenReturn(blobClient);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getCopyStatus()).thenReturn(CopyStatusType.SUCCESS);

        final boolean result = service.isBlobAvailable("testBlob");

        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalse_whenCopyStatusIsNotSuccess() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobProperties blobProperties = mock(BlobProperties.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);

        when(containerClientMock.getBlobClient("testBlob")).thenReturn(blobClient);
        when(blobClient.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getCopyStatus()).thenReturn(CopyStatusType.PENDING);

        final boolean result = service.isBlobAvailable("testBlob");

        assertThat(result).isFalse();
    }

    @Test
    void shouldReturnFalse_whenBlobPropertiesIsNull() {
        final BlobClient blobClient = mock(BlobClient.class);
        final BlobContainerClient containerClientMock = mock(BlobContainerClient.class);
        final BlobServiceClient serviceClientMock = mock(BlobServiceClient.class);
        final BlobClientService service = new BlobClientService(containerClientMock, serviceClientMock);

        when(containerClientMock.getBlobClient("testBlob")).thenReturn(blobClient);
        when(blobClient.getProperties()).thenReturn(null);

        final boolean result = service.isBlobAvailable("testBlob");

        assertThat(result).isFalse();
    }
}
