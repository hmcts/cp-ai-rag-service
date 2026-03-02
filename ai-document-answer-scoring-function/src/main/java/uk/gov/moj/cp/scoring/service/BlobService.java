package uk.gov.moj.cp.scoring.service;

import static uk.gov.moj.cp.ai.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cp.ai.FunctionEnvironment;
import uk.gov.moj.cp.ai.exception.BlobParsingException;
import uk.gov.moj.cp.ai.service.BlobClientService;
import uk.gov.moj.cp.ai.util.StringUtil;

import com.azure.storage.blob.BlobClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlobService.class);

    private final BlobClientService blobClientService;

    public BlobService() {
        final FunctionEnvironment env = FunctionEnvironment.get();
        this.blobClientService = new BlobClientService(env.storageConfig().evalPayloadsContainer());
    }

    public BlobService(final BlobClientService blobClientService) {
        this.blobClientService = blobClientService;
    }

    public <T> T readBlob(final String filename, Class<T> payloadClass) throws BlobParsingException {

        if (StringUtil.isNullOrEmpty(filename)) {
            throw new BlobParsingException("Unable to process blob as file name is null or empty");
        }

        try {
            LOGGER.info("Reading blob with filename: {}", filename);
            final BlobClient blobClient = blobClientService.getBlobClient(filename);
            final String blobPayload = blobClient.downloadContent().toString();
            return getObjectMapper().readValue(blobPayload, payloadClass);
        } catch (JsonProcessingException e) {
            throw new BlobParsingException("Unable to process blob with filename: " + filename, e);
        }
    }
}
