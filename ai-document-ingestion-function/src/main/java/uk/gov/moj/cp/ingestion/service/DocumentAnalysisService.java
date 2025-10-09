package uk.gov.moj.cp.ingestion.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.DocumentProcessingException;

import java.time.OffsetDateTime;

import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClient;
import com.azure.ai.formrecognizer.documentanalysis.DocumentAnalysisClientBuilder;
import com.azure.ai.formrecognizer.documentanalysis.models.AnalyzeResult;
import com.azure.ai.formrecognizer.documentanalysis.models.OperationResult;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.logging.ClientLogger;
import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

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

    public AnalyzeResult analyzeDocument(QueueIngestionMetadata queueIngestionMetadata)
            throws DocumentProcessingException {
        String documentName = queueIngestionMetadata.documentName();
        String documentUrl = queueIngestionMetadata.blobUrl();
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

