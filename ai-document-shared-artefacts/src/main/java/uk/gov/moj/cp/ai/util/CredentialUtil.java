package uk.gov.moj.cp.ai.util;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

public class CredentialUtil {

    private CredentialUtil() {
        // Private constructor to prevent instantiation
    }

    private static class CredentialSingletonHolder {
        // This is where the lazy, thread-safe initialization happens.
        // The JVM guarantees that the class is initialized safely and only once.
        private static final DefaultAzureCredential INSTANCE = new DefaultAzureCredentialBuilder().build();
    }

    public static DefaultAzureCredential getCredentialInstance() {
        // No synchronization needed here.
        return CredentialSingletonHolder.INSTANCE;
    }
}
