package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.ai.util.StringUtil;

import java.nio.charset.StandardCharsets;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobPersistenceService.class);

    private final BlobClientService blobClientService;

    public BlobPersistenceService(final String documentContainerName) {
        this.blobClientService = new BlobClientService(documentContainerName);
    }

    public BlobPersistenceService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public void saveBlob(final String filename, final String payload) {
        blobClientService.addBlob(filename, payload);
        LOGGER.info("Blob '{}' saved successfully.", filename);
    }

    public <T> T readBlob(final String filename, Class<T> payloadClass) throws BlobParsingException {

        if (StringUtil.isNullOrEmpty(filename)) {
            throw new BlobParsingException("Unable to process blob as file name is null or empty");
        }

        try {
            LOGGER.info("Reading blob with filename: {}", filename);
            final BlobClient blobClient = blobClientService.getBlobClient(filename);
            final BinaryData data = blobClient.downloadContent();
            final String blobPayload = new String(data.toBytes(), StandardCharsets.UTF_8);
            return getObjectMapper().readValue(blobPayload, payloadClass);
        } catch (JsonProcessingException e) {
            throw new BlobParsingException("Unable to process blob with filename: " + filename, e);
        }
    }
}
