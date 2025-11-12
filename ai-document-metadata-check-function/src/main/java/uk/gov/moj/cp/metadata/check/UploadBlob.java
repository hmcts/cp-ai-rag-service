package uk.gov.moj.cp.metadata.check;

import static java.util.UUID.randomUUID;

import uk.gov.moj.cp.ai.service.BlobClientFactory;

import java.time.Duration;
import java.util.Map;

import com.azure.core.util.polling.SyncPoller;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.options.BlobBeginCopyOptions;

public class UploadBlob {

    public static void main(String[] args) {
        String sourceBlobUrl = "https://sasteairag.blob.core.windows.net/documents/0aa2b04f-eca1-411d-b88f-4a12231a1e94_20251111.pdf";

        final String documentId = randomUUID().toString();
        Map<String, String> metadata = Map.of(
                "document_id", documentId
        );

        String blobName = documentId + ".pdf";

        BlobClientFactory blobClientFactory = new BlobClientFactory("https://sasteairag.blob.core.windows.net/", "documents");
        final BlobClient destinationBlobClient = blobClientFactory.getBlobClient(blobName);
        BlobBeginCopyOptions options = new BlobBeginCopyOptions(sourceBlobUrl).setMetadata(metadata).setPollInterval(Duration.ofSeconds(10));

        SyncPoller<BlobCopyInfo, Void> poller = destinationBlobClient.beginCopy(options);

        // Retrieve the ETag from the final result (or use poller.getFinalResult())
        BlobCopyInfo copyInfo = poller.waitForCompletion().getValue();
        System.out.println("Blob copy successful. File URL: " + destinationBlobClient.getBlobUrl());

    }
}
