package uk.gov.moj.cp.migration.index;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import uk.gov.moj.cp.ai.model.ChunkedEntry;

import java.util.List;

import com.azure.search.documents.SearchIndexingBufferedSender;
import org.junit.jupiter.api.Test;

class BufferedSenderUploaderTest {

    @Test
    void uploadDelegatesToTheSenderAndCloseFlushesIt() {
        @SuppressWarnings("unchecked")
        final SearchIndexingBufferedSender<ChunkedEntry> sender = mock(SearchIndexingBufferedSender.class);
        final BufferedSenderUploader uploader = new BufferedSenderUploader(sender);
        final List<ChunkedEntry> docs = List.of(ChunkedEntry.builder().id("x").build());

        uploader.upload(docs);
        uploader.close();

        verify(sender).addUploadActions(docs);
        verify(sender).close();
    }
}
