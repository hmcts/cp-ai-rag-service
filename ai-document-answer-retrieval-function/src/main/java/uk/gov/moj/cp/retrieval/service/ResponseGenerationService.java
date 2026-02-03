package uk.gov.moj.cp.retrieval.service;

import static uk.gov.moj.cp.ai.util.StringUtil.isNullOrEmpty;
import static uk.gov.moj.cp.retrieval.langfuse.LangfuseClientFactory.compilePrompt;
import static uk.gov.moj.cp.retrieval.langfuse.LangfuseClientFactory.getClient;
import static uk.gov.moj.cp.retrieval.langfuse.LangfuseConfig.getTracer;

import uk.gov.moj.cp.ai.model.ChunkedEntry;
import uk.gov.moj.cp.ai.model.KeyValuePair;
import uk.gov.moj.cp.ai.service.ChatService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.langfuse.client.resources.prompts.PromptsClient;
import com.langfuse.client.resources.prompts.types.Prompt;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseGenerationService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an expert Legal Advisor.
            Who goes through the complete case document before responding and responds with every single detail to answer user's query.
            Understand any kind of user query and respond accordingly\
            Respond purely based on the provided legal document:\s
             \
            **Retrieved Documents:**
            %s\
            
            **Instructions:**
            1.  **Strictly adhere to the provided documents:** Answer the user's query *only* using information found within the {Retrieved Documents}\
            2.  **Provide Source for all factual statements:** For every factual statement you make you should include the citation
            3.  **CRITICAL: Single Placeholder Rule (One Statement = One Citation ID per documentId):** For any single factual statement, regardless of how many pages within the same document, or fragments it draws upon, you **MUST** use only **ONE sequential numerical placeholder** (e.g., [1]). 
                Immediately place this single placeholder after the factual statement. **NEVER** use multiple placeholders next to each other for the same document (e.g., [3][4] is **FORBIDDEN** for same documentId).
            4.  **JSON Source Aggregation:**
                * If a single factual statement (linked to one placeholder, e.g., [1]) is supported by sources from **multiple documents** or **multiple page ranges**, you **MUST** include all necessary sources in the **sme objects** in the final JSON array.
            5.  **JSON Page Formatting (For Single-Document Sources):** When a single source (defined by `documentId`) requires multiple pages:
                * **`individualPageNumbers`:** List all cited page numbers, comma-separated (e.g., "17,18,19,20,21").
                * **`pageNumbers`:** Compress consecutive page numbers using a hyphen, followed by non-consecutive pages (e.g., "17-19,20,21" or "10-12,14,20").
            6.  **Guardrail Against Placeholder Generation:** To ensure compliance and prevent misinterpretation, you **MUST NOT** use numbers enclosed in square brackets (e.g., [1], [2], [3], etc.) for any purpose other than the mandatory source citation described in Instruction 3. If you need to list or enumerate items in the text, use parentheses (e.g., (1), (2), (3)), Roman numerals (e.g., (i), (ii)), or standard bullet points.
            7.  **CRITICAL HEADING HIERARCHY:** For accessibility compliance (DAC/NFT level), you MUST follow proper heading structure:
                - NEVER use h1 (#) headings in your response as the page already has an h1
                - Use h2 (##) for main question titles and section headings
                - Use h3 (###) for subheadings
                - Use h4 (####) for sub-subheadings, and so on in descending order
                - This is mandatory for accessibility compliance
            8.  **Data Output (JSON Array):** Immediately after your complete answer text, you MUST generate a single, minified JSON array containing all citation details. This data array must be wrapped in the exact literal tags: `<FACT_MAP_JSON>` and `</FACT_MAP_JSON>`.
            9.  **JSON Schema:** Each object in the array must include the following keys:
                - `"citationId"`: The sequential number used in the answer placeholder (e.g., 1, 2, 3).
                - `"documentFilename"`: The filename of the source document.
                - `"pageNumbers"`: The page numbers string with consecutive sequential page numbers hyphenated (e.g., "10-12,14,20").
                - `"individualPageNumbers"`: The page numbers string (e.g., "10,11,12,14,20").
                - `"documentId"`: The documentId GUID.
            Provide the answer in a well written professional format.
            At the end of response, do not ask user for a follow up query.
            
            Additional context: %s""";

    private final ChatService chatService;
    private final CitationProcessor citationProcessor;

    public ResponseGenerationService() {
        String endpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String deploymentName = System.getenv("AZURE_OPENAI_CHAT_DEPLOYMENT_NAME");

        // Using managed identity - pass null for API key to enable managed  identity
        chatService = new ChatService(endpoint, deploymentName);
        citationProcessor = new CitationProcessor();
    }

    public ResponseGenerationService(final ChatService chatService, final CitationProcessor citationProcessor) {
        this.chatService = chatService;
        this.citationProcessor = citationProcessor;
    }

    public String generateResponse(final String userQuery, final List<ChunkedEntry> chunkedEntries, final String userQueryPrompt) {
        LOGGER.info("Generating LLM response for query: {}", userQuery);

        if(null == chunkedEntries || chunkedEntries.isEmpty()) {
            LOGGER.warn("No matching data from search database retrieved for query: {}", userQuery);
            return "No response generated by the service.";
        }

        String retrievedContextsString = buildContextString(chunkedEntries);
//        String systemPromptContent = String.format(SYSTEM_PROMPT_TEMPLATE, retrievedContextsString,
//                userQueryPrompt != null ? userQueryPrompt : "");


        final PromptsClient prompts = getClient().prompts();
        prompts.list().getData().forEach(p -> LOGGER.info("Prompt available: {}", p.getName()));
        final Prompt prompt = prompts.get("cp-summarisation-system-prompt");
        String systemPromptContent = compilePrompt(prompt.getText().get().getPrompt(), Map.of(
                "retrieved_documents", retrievedContextsString,
                "query_prompt", userQueryPrompt != null ? userQueryPrompt : ""
        ));

        Span genSpan = getTracer().spanBuilder("llm-generation").startSpan();
        try {
            genSpan.setAttribute("langfuse.prompt.name", prompt.getText().get().getName());
            genSpan.setAttribute("langfuse.prompt.version", prompt.getText().get().getVersion());
            genSpan.setAttribute("gen_ai.prompt", systemPromptContent);
            genSpan.setAttribute("gen_ai.system", "openai");
            genSpan.setAttribute("gen_ai.request.model", "gpt-40");
            return chatService.callModel(systemPromptContent, userQuery, String.class)
                    .filter(response -> !isNullOrEmpty(response))
                    .map(response -> {
                        genSpan.setAttribute("gen_ai.completion.0.content", response);
                        genSpan.end();
                        String trimmedResponse = citationProcessor.processAndFormatCitations(response);
                        LOGGER.info("LLM Raw Response length = {}", trimmedResponse.length());
                        return trimmedResponse;
                    })
                    .orElseGet(() -> {
                        genSpan.setAttribute("gen_ai.completion.0.content", "");
                        genSpan.end();
                        LOGGER.warn("LLM returned no response.");
                        return "No response generated by the service.";
                    });
        } catch (Exception e) {
            genSpan.recordException(e);
            genSpan.end();
            LOGGER.error("Error generating response for user query: {}", userQuery, e);
            return "An error occurred while generating the response.";
        }
    }

    private String buildContextString(final List<ChunkedEntry> chunkedEntries) {
        if (chunkedEntries == null || chunkedEntries.isEmpty()) {
            return "No relevant documents were retrieved for this query";
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (ChunkedEntry entry : chunkedEntries) {
            // Extract material name from customMetadata, and change map it to DocumentFileName
            String documentFileName = extractMaterialName(entry)
                    .orElse(entry.documentFileName());

            contextBuilder.append("DOCUMENT_ID: ").append(entry.documentId())
                    .append(", DOCUMENT_FILENAME: ").append(documentFileName);
            if (entry.pageNumber() != null) {
                contextBuilder.append(", PAGE_NUMBER: ").append(entry.pageNumber());
            }
            contextBuilder.append("\nDOCUMENT_CONTENT: ").append(entry.chunk()).append("\n\n");
        }
        return contextBuilder.toString();
    }

    private Optional<String> extractMaterialName(ChunkedEntry entry) {
        if (entry.customMetadata() == null || entry.customMetadata().isEmpty()) {
            return Optional.empty();
        }

        return entry.customMetadata().stream()
                .filter(pair -> "material_name".equals(pair.key()))
                .map(KeyValuePair::value)
                .filter(value -> !isNullOrEmpty(value))
                .findFirst();
    }
}