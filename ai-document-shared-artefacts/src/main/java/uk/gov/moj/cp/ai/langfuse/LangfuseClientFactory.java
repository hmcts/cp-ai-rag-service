package uk.gov.moj.cp.ai.langfuse;

import static uk.gov.moj.cp.ai.util.EnvVarUtil.getRequiredEnv;

import uk.gov.moj.cp.ai.util.EnvVarUtil;

import java.util.Map;

import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.prompts.PromptsClient;
import com.langfuse.client.resources.prompts.types.Prompt;

public class LangfuseClientFactory {

    public static LangfuseClient getClient() {
        return LangfuseClient.builder()
                .url("http://localhost:3000")
                .credentials(getRequiredEnv("LANGFUSE_PUBLIC_KEY"), getRequiredEnv("LANGFUSE_SECRET_KEY"))
                .build();
    }

    public static String compilePrompt(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            // Replaces {{key}} with value
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    public static void main(String[] args) {
        final PromptsClient prompts = getClient().prompts();
        prompts.list().getData().forEach(p -> System.out.println("Prompt available: " + p.getName()));
        final Prompt prompt = prompts.get("cp-summarisation-system-prompt");
    }
}
