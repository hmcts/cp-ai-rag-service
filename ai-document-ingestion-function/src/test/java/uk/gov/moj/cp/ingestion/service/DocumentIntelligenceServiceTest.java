package uk.gov.moj.cp.ingestion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.util.List;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeOperationDetails;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.core.util.polling.SyncPoller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIntelligenceServiceTest {

    @Mock
    private DocumentIntelligenceClient mockDocumentIntelligenceClient;

    @Mock
    private SyncPoller<AnalyzeOperationDetails, AnalyzeResult> mockPoller;

    @Mock
    private AnalyzeResult mockAnalyzeResult;

    private DocumentIntelligenceService documentIntelligenceService;

    @BeforeEach
    void setUp() {
        documentIntelligenceService = new DocumentIntelligenceService(mockDocumentIntelligenceClient);
    }

    @Test
    @DisplayName("Should analyze document successfully")
    void shouldAnalyzeDocumentSuccessfully() throws DocumentProcessingException {
        when(mockDocumentIntelligenceClient.beginAnalyzeDocument(anyString(), any(AnalyzeDocumentOptions.class)))
                .thenReturn(mockPoller);
        when(mockPoller.getFinalResult()).thenReturn(mockAnalyzeResult);
        when(mockAnalyzeResult.getPages()).thenReturn(List.of());

        AnalyzeResult result = documentIntelligenceService.analyzeDocument("test-document", "https://example.com/test.pdf");

        assertNotNull(result);
        verify(mockDocumentIntelligenceClient).beginAnalyzeDocument(anyString(), any(AnalyzeDocumentOptions.class));
        verify(mockPoller).getFinalResult();
    }

    @Test
    @DisplayName("Should throw exception for invalid document name")
    void shouldThrowExceptionForInvalidDocumentName() {
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument(null, "some url"));
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument("", "some url"));
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument("   ", "some url"));
    }

    @Test
    @DisplayName("Should throw exception for invalid document URL")
    void shouldThrowExceptionForInvalidDocumentUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument("test-document", null));
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument("test-document", ""));
        assertThrows(IllegalArgumentException.class, () ->
                documentIntelligenceService.analyzeDocument("test-document", "   "));
    }

    @Test
    @DisplayName("Should throw exception for invalid endpoint")
    void shouldThrowExceptionForInvalidEndpoint() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new DocumentIntelligenceService(""));

        assertEquals("Document Intelligence Endpoint cannot be null or empty", exception.getMessage());
    }

    @Test
    @DisplayName("Should handle analysis failure gracefully")
    void shouldHandleAnalysisFailureGracefully() throws DocumentProcessingException {
        when(mockDocumentIntelligenceClient.beginAnalyzeDocument(anyString(), any(AnalyzeDocumentOptions.class)))
                .thenThrow(new RuntimeException("API error"));

        DocumentProcessingException exception = assertThrows(DocumentProcessingException.class, () ->
                documentIntelligenceService.analyzeDocument("test-document", "https://example.com/test.pdf"));

        assertEquals("Failed to analyze document with name : test-document . Error: API error", exception.getMessage());
        verify(mockDocumentIntelligenceClient).beginAnalyzeDocument(anyString(), any(AnalyzeDocumentOptions.class));
    }
}
