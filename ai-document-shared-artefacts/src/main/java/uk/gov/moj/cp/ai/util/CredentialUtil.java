package uk.gov.moj.cp.ai.util;

import static uk.gov.moj.cp.ai.SharedSystemVariables.AZURE_CLIENT_ID;
import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.exception.EnvVarNotFoundException;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CredentialUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialUtil.class);

    private CredentialUtil() {
    }

    public static DefaultAzureCredential getDefaultAzureCredentialBuilder() {
        final DefaultAzureCredentialBuilder defaultAzureCredentialBuilder = new DefaultAzureCredentialBuilder();
        try {
            final String userAssignedManagedIdentity = getRequiredEnv(AZURE_CLIENT_ID);
            LOGGER.info("User assigned managed identity credential available");
            defaultAzureCredentialBuilder.managedIdentityClientId(userAssignedManagedIdentity);
        } catch (final EnvVarNotFoundException e) {
            LOGGER.warn("User assigned managed identity credential NOT available.  Will use fallback strategy if available");
        }

        return defaultAzureCredentialBuilder.build();
    }
}
