package uk.gov.moj.cp.ingestion.service;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import com.azure.core.util.logging.ClientLogger;


public class DocumentProcessingOrchestrator {

    private final DocumentAnalysisService analysisService;
    private final ClientLogger logger = new ClientLogger(DocumentProcessingOrchestrator.class);


    public DocumentProcessingOrchestrator() {
        this.analysisService = new DocumentAnalysisService(
                System.getenv("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT"),
                System.getenv("AZURE_DOCUMENT_INTELLIGENCE_KEY")
        );
    }

    public DocumentProcessingOrchestrator(DocumentAnalysisService analysisService) {
        this.analysisService = analysisService;

    }

    public void processDocument(QueueIngestionMetadata queueMetadata)
            throws DocumentProcessingException {
        logger.info("Starting document processing pipeline for: {}", queueMetadata.documentName());

        try {
            // Step 1: Analyze document with Azure Document Intelligence
//            AnalyzeResult analysisResult = analysisService.analyzeDocument(queueMetadata.blobUrl(),
//                    queueMetadata.documentName());


        } catch (Exception e) {
            String errorMsg = "Unexpected error processing document: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new DocumentProcessingException(errorMsg, e);
        }
    }

}
