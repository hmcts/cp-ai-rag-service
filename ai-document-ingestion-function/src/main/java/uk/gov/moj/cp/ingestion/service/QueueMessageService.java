package uk.gov.moj.cp.ingestion.service;

import uk.gov.moj.cp.ai.model.QueueIngestionMetadata;
import uk.gov.moj.cp.ingestion.exception.QueueMessageParsingException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple service to process queue messages containing document ingestion metadata.
 */
public class QueueMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueueMessageService.class);
    private final ObjectMapper objectMapper;

    public QueueMessageService() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parses a queue message JSON into a QueueIngestionMetadata object.
     *
     * @param queueMessage The JSON queue message
     * @return QueueIngestionMetadata object with parsed data
     */
    public QueueIngestionMetadata parseQueueMessage(String queueMessage) throws QueueMessageParsingException {
        LOGGER.info("Deserializing queue message...");

        try {
            return objectMapper.readValue(queueMessage, QueueIngestionMetadata.class);
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to deserialize queue message: {}", queueMessage, e);
            throw new QueueMessageParsingException("Error deserializing queue message", e);
        }
    }
}

