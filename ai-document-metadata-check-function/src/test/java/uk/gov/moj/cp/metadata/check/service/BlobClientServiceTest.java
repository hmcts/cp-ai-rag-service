package uk.gov.moj.cp.metadata.check.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlobClientServiceTest {

    private BlobClientService blobClientService;

    @BeforeEach
    void setUp() {
        // Set required system properties for testing
        System.setProperty("AzureWebJobsStorage", "UseDevelopmentStorage=true");
        System.setProperty("DOCUMENT_CONTAINER_NAME", "testcontainer");
        blobClientService = new BlobClientService();
    }

    @Test
    @DisplayName("Creates blob client successfully")
    void shouldCreateBlobClient() {
        // given
        String documentName = "test-document.pdf";

        // when
        var blobClient = blobClientService.getBlobClient(documentName);

        // then
        assertNotNull(blobClient);
    }
}
