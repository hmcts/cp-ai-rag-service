package uk.gov.moj.cp.ai.client.identity;

import com.microsoft.azure.functions.HttpRequestMessage;

/**
 * The single point of change for extracting the caller's client identity. When APIM's
 * identity mechanism is finalised (JWT/cookie/header), only the implementation changes.
 */
public interface ClientIdentityResolver {

    /**
     * @return a {@link ClientContext} carrying the validated clientId (enforcement on) or an empty,
     *         unenforced context (flag off).
     * @throws ClientIdentityException enforcement on AND identity missing/blank/malformed → maps to 401.
     */
    ClientContext resolve(HttpRequestMessage<?> request);
}
