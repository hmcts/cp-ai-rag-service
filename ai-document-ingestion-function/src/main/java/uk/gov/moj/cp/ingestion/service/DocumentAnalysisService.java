package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ingestion.client.DocumentAnalysisClientFactory;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import com.azure.ai.formrecognizer.documentanalysis.models.AnalyzeResult;
import com.azure.ai.formrecognizer.documentanalysis.models.OperationResult;
import com.azure.core.util.polling.SyncPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentAnalysisService.class);

    private static final String MODEL_ID = "prebuilt-layout";
    private final DocumentAnalysisClient documentAnalysisClient;

    public DocumentAnalysisService(String endpoint) {
        if (isNullOrEmpty(endpoint)) {
            throw new IllegalArgumentException("Document Intelligence Endpoint cannot be null or empty");
        }

        LOGGER.info("Connecting to Document Intelligence endpoint '{}'", endpoint);

        this.documentAnalysisClient = DocumentAnalysisClientFactory.getInstance(endpoint);

        LOGGER.info("Initialized Document Intelligence client with managed identity.");
    }

    public AnalyzeResult analyzeDocument(final String documentName,
                                         final String documentUrl)
            throws DocumentProcessingException {

        LOGGER.info("Starting document analysis for: {}", documentName);

        try {
            SyncPoller<OperationResult, AnalyzeResult> poller =
                    documentAnalysisClient.beginAnalyzeDocumentFromUrl(MODEL_ID,
                            documentUrl);

            AnalyzeResult result = poller.getFinalResult();

            LOGGER.info("Successfully analyzed document: {} with {} pages",
                    documentName, result.getPages().size());

            return result;

        } catch (Exception e) {
            String errorMsg = "Failed to analyze document with name : " + documentName + " . Error: " + e.getMessage();
            LOGGER.error(errorMsg, e);
            throw new DocumentProcessingException(errorMsg, e);
        }
    }
}

