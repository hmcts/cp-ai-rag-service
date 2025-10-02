package uk.gov.moj.cp.ingestion.service;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.moj.cp.ai.model.DocumentIngestionOutcome;

import java.time.Instant;
import java.util.Map;

/**
 * Service for updating document ingestion outcomes in Azure Table Storage.
 * This service updates the existing record created by the metadata check function.
 */
public class IngestionOutcomeService {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionOutcomeService.class);
    
    // Status constants matching the metadata check function
    private static final String INGESTION_SUCCESS = "INGESTION_SUCCESS";
    private static final String INGESTION_FAILED = "INGESTION_FAILED";
    private static final String INGESTION_SUCCESS_REASON = "Document ingestion completed successfully";
    private static final String INGESTION_FAILED_REASON = "Document ingestion failed during processing";
    
    private final TableClient tableClient;
    
    public IngestionOutcomeService(String storageConnectionString, String tableName) {
        if (storageConnectionString == null || storageConnectionString.trim().isEmpty()) {
            throw new IllegalArgumentException("Storage connection string cannot be null or empty");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        
        this.tableClient = new TableClientBuilder()
                .connectionString(storageConnectionString)
                .tableName(tableName)
                .buildClient();
    }
    
    /**
     * Updates the existing record to INGESTION_SUCCESS status.
     * This updates the record created by the metadata check function.
     * 
     * @param documentId The document ID
     * @param documentName The document name
     * @param additionalMetadata Additional metadata from the queue message
     */
    public void recordSuccess(String documentId, String documentName, Map<String, String> additionalMetadata) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setDocumentId(documentId);
        outcome.setDocumentName(documentName);
        outcome.setStatus(INGESTION_SUCCESS);
        outcome.setReason(INGESTION_SUCCESS_REASON);
        outcome.setTimestamp(Instant.now().toString());
        
        updateOutcome(outcome);
    }
    
    /**
     * Updates the existing record to INGESTION_FAILED status.
     * This updates the record created by the metadata check function.
     * 
     * @param documentId The document ID
     * @param documentName The document name
     * @param reason The failure reason
     * @param additionalMetadata Additional metadata from the queue message
     */
    public void recordFailure(String documentId, String documentName, String reason, Map<String, String> additionalMetadata) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setDocumentId(documentId);
        outcome.setDocumentName(documentName);
        outcome.setStatus(INGESTION_FAILED);
        outcome.setReason(reason);
        outcome.setTimestamp(Instant.now().toString());
        
        updateOutcome(outcome);
    }
    
    /**
     * Updates the existing record to INGESTION_FAILED status for unknown failures.
     * This updates the record created by the metadata check function.
     * 
     * @param documentId The document ID
     * @param documentName The document name
     * @param reason The failure reason
     */
    public void recordUnknownFailure(String documentId, String documentName, String reason) {
        DocumentIngestionOutcome outcome = new DocumentIngestionOutcome();
        outcome.setDocumentId(documentId);
        outcome.setDocumentName(documentName);
        outcome.setStatus(INGESTION_FAILED);
        outcome.setReason("Unknown failure: " + reason);
        outcome.setTimestamp(Instant.now().toString());
        
        updateOutcome(outcome);
    }
    
    /**
     * Updates the existing record in Azure Table Storage.
     * Uses upsertEntity which will update if exists, create if not.
     * 
     * @param outcome The outcome to update
     */
    private void updateOutcome(DocumentIngestionOutcome outcome) {
        try {
            // Use the same toTableEntity() method as the metadata check function
            // This ensures the same partition key and row key are used for updates
            tableClient.upsertEntity(outcome.toTableEntity());
            
            LOGGER.info("Successfully updated ingestion outcome for document: {} (ID: {}) with status: {}", 
                    outcome.getDocumentName(), outcome.getDocumentId(), outcome.getStatus());
            
        } catch (Exception e) {
            LOGGER.error("Failed to update ingestion outcome for document: {} (ID: {})", 
                    outcome.getDocumentName(), outcome.getDocumentId(), e);
            // Don't throw exception to avoid masking the original processing error
        }
    }
}
