package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.ai.util.StringUtil.validateNullOrEmpty;

import uk.gov.moj.cp.ai.util.StringUtil;
import uk.gov.moj.cp.ingestion.client.DocumentIntelligenceClientFactory;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeOperationDetails;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.core.util.polling.SyncPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentIntelligenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIntelligenceService.class);

    private static final String MODEL_ID = "prebuilt-layout";
    private final DocumentIntelligenceClient documentIntelligenceClient;

    public DocumentIntelligenceService(String endpoint) {
        if (isNullOrEmpty(endpoint)) {
            throw new IllegalArgumentException("Document Intelligence Endpoint cannot be null or empty");
        }

        LOGGER.info("Connecting to Document Intelligence endpoint '{}'", endpoint);

        this.documentIntelligenceClient = DocumentIntelligenceClientFactory.getInstance(endpoint);

        LOGGER.info("Initialized Document Intelligence client with managed identity.");
    }

    public DocumentIntelligenceService(final DocumentIntelligenceClient documentIntelligenceClient) {
        this.documentIntelligenceClient = documentIntelligenceClient;
    }

    public AnalyzeResult analyzeDocument(final String documentName,
                                         final String documentUrl)
            throws DocumentProcessingException {

        validateNullOrEmpty(documentName, "Document name cannot be null or empty");
        validateNullOrEmpty(documentUrl, "Document URL cannot be null or empty");

        LOGGER.info("Starting document analysis for: {}", documentName);

        try {
            AnalyzeDocumentOptions options = new AnalyzeDocumentOptions(documentUrl);

            SyncPoller<AnalyzeOperationDetails, AnalyzeResult> poller =
                    documentIntelligenceClient.beginAnalyzeDocument(MODEL_ID,
                            options);

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

