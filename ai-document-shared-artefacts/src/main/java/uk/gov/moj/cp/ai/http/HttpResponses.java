package uk.gov.moj.cp.ai.http;

import static uk.gov.moj.cp.ai.util.ObjectToJsonConverter.convert;

import uk.gov.hmcts.cp.openapi.model.RequestErrored;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

/**
 * Shared builders for HTTP responses that must be identical across the HTTP-triggered functions.
 *
 * <p>Keeps the enforcement 401 in one place so every function returns the same status and body
 * shape, matching each function's existing {@code RequestErrored}-bodied error convention.
 */
public final class HttpResponses {

    static final String UNAUTHORIZED_MESSAGE = "Missing or invalid client identity";

    private HttpResponses() {
        // Utility class
    }

    /**
     * Builds a {@code 401 Unauthorized} response carrying a {@link RequestErrored} JSON body, used
     * when the resolved client identity is rejected.
     */
    public static HttpResponseMessage unauthorized(final HttpRequestMessage<?> request) {
        return request.createResponseBuilder(HttpStatus.UNAUTHORIZED)
                .header("Content-Type", "application/json")
                .body(convert(new RequestErrored(UNAUTHORIZED_MESSAGE)))
                .build();
    }
}
