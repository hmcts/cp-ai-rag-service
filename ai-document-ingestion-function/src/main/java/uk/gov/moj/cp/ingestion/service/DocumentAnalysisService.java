package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClientBuilder;
import com.azure.ai.formrecognizer.documentanalysis.models.AnalyzeResult;
import com.azure.ai.formrecognizer.documentanalysis.models.OperationResult;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.logging.ClientLogger;
import com.azure.core.util.polling.SyncPoller;

public class DocumentAnalysisService {

    private static final String MODEL_ID = "prebuilt-layout";
    private final DocumentAnalysisClient documentAnalysisClient;
    private final ClientLogger logger = new ClientLogger(DocumentAnalysisService.class);


    public DocumentAnalysisService(String endpoint, String apiKey) {
        if (isNullOrEmpty(endpoint) || isNullOrEmpty(apiKey)) {
            throw new IllegalArgumentException("Document Intelligence Endpoint and API key cannot be null or empty");
        }

        this.documentAnalysisClient = new DocumentAnalysisClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .buildClient();
    }

    public AnalyzeResult analyzeDocument(final String documentName,
                                         final String documentUrl)
            throws DocumentProcessingException {

        logger.info("Starting document analysis for: {}", documentName);

        try {
            SyncPoller<OperationResult, AnalyzeResult> poller =
                    documentAnalysisClient.beginAnalyzeDocumentFromUrl(MODEL_ID,
                            documentUrl);

            AnalyzeResult result = poller.getFinalResult();

            logger.info("Successfully analyzed document: {} with {} pages",
                    documentName, result.getPages().size());

            return result;

        } catch (Exception e) {
            String errorMsg = "Failed to analyze document: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new DocumentProcessingException(errorMsg, e);
        }
    }
}

