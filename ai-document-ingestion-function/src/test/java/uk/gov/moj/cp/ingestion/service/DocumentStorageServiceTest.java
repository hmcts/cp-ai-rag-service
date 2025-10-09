package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import uk.gov.moj.cp.ingestion.exception.DocumentUploadException;
import uk.gov.moj.cp.ingestion.model.PageChunk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class DocumentStorageServiceTest {

    private DocumentStorageService documentStorageService;

    @BeforeEach
    void setUp() {
        documentStorageService = new DocumentStorageService("https://test-search.endpoint", "test-index", "test-key");
    }

    @Test
    @DisplayName("Handle Null Chunks List")
    void shouldHandleNullChunksList() throws Exception {
        // when & then
        assertThrows(NullPointerException.class,
                () -> documentStorageService.uploadChunks(null));
    }

    @Test
    @DisplayName("Handle Empty Chunks List")
    void shouldHandleEmptyChunksList() throws Exception {
        // given
        List<PageChunk> emptyChunks = Collections.emptyList();

        // when & then
        assertDoesNotThrow(() -> documentStorageService.uploadChunks(emptyChunks));
    }

    @Test
    @DisplayName("Service Constructor Works")
    void shouldCreateServiceWithValidParameters() {
        // when
        DocumentStorageService service = new DocumentStorageService("https://test-endpoint", "test-index", "test-key");

        // then
        assertNotNull(service);
    }

    @Test
    @DisplayName("Service Constructor with Null Admin Key")
    void shouldCreateServiceWithNullAdminKey() {
        // when
        DocumentStorageService service = new DocumentStorageService("https://test-endpoint", "test-index", null);

        // then
        assertNotNull(service);
    }

    @Test
    @DisplayName("Service Constructor with Empty Admin Key")
    void shouldCreateServiceWithEmptyAdminKey() {
        // when
        DocumentStorageService service = new DocumentStorageService("https://test-endpoint", "test-index", "");

        // then
        assertNotNull(service);
    }
}