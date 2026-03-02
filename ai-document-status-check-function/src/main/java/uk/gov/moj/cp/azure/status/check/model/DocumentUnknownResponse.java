package uk.gov.moj.cp.azure.status.check.model;

public record DocumentUnknownResponse(
        String documentName,
        String reason
) {
}

