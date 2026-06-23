package uk.gov.moj.cp.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentStatusTest {

    @Test
    @DisplayName("Every status exposes a non-blank reason")
    void everyStatusHasReason() {
        for (final DocumentStatus status : DocumentStatus.values()) {
            assertThat(status.getReason()).as(status.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Deprecated Flow B statuses are retained with their reasons")
    @SuppressWarnings("deprecation")
    void deprecatedFlowBStatusesRetained() {
        assertThat(DocumentStatus.METADATA_VALIDATED.getReason())
                .isEqualTo("Document metadata validated and sent to queue");
        assertThat(DocumentStatus.INVALID_METADATA.getReason())
                .isEqualTo("Invalid or incomplete nested metadata detected");
    }
}
