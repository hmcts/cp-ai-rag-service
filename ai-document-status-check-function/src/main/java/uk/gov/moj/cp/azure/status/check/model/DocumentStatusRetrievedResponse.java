package uk.gov.moj.cp.azure.status.check.model;

public record DocumentStatusRetrievedResponse(
        String documentId,
        String documentName,
        String status,
        String reason,
        String lastUpdated
) {
}

