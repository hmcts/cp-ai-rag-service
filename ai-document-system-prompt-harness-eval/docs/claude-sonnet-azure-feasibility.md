# Claude Sonnet 4.6 on Azure (AI Foundry) — feasibility and harness integration

> **Purpose & status.** Building on the cross-model evaluations
> (`system-prompt-evaluation-cross-model.md`, `system-prompt-evolution-summary.md`), this
> exercise explores evaluating an **Anthropic model — Claude Sonnet 4.6, hosted on Azure via
> Microsoft AI Foundry** — against the production **GPT-4o baseline**, using this module's
> harness. It is an evaluation-only exploration: **nothing here touches production**, the
> function apps, or the shared artefacts library. Its conclusions inform whether a Claude
> comparison run is worth doing and what its results can (and cannot) tell us.

## 1. Feasibility summary

**Verdict: moderately easy — a clean seam, but a different API.** Claude on Azure is served
via the **Anthropic Messages API**, not Azure OpenAI chat completions, so the existing
`azure-ai-openai` client cannot call it. Integration required:

1. **A harness-local chat client** — `AnthropicChatService` implements the shared
   `ChatService` interface (one method), so it slots into the production
   `ResponseGenerationService` the harness already drives. Built on the official
   `com.anthropic:anthropic-java` SDK with its `anthropic-java-foundry` backend
   (Azure AI Foundry routing + auth). Both are **harness-only dependencies** — deliberately
   kept out of the function apps and `ai-document-shared-artefacts`.
2. **Per-model provider routing in the harness** — `HARNESS_LLM_DEPLOYMENTS` previously
   assumed every model shared one endpoint and one provider. Entries may now carry an
   `anthropic:` prefix, e.g.:

   ```
   HARNESS_LLM_DEPLOYMENTS=gpt-4o-response-generation,anthropic:claude-sonnet-4-6
   ```

   Unprefixed entries behave exactly as before (production `ChatServiceFactory` against
   `AZURE_OPENAI_ENDPOINT`), so existing `.env` files keep working. Both models run in one
   matrix and appear side by side in the same summary/consistency/detail tables.

   Any entry may also carry a **per-model endpoint** via an `@https://...` suffix, overriding
   the provider's global endpoint env var for that model only — so models hosted on different
   Azure resources (e.g. the OpenAI resource and a separate Foundry resource, or two Foundry
   resources) share one comparison run:

   ```
   HARNESS_LLM_DEPLOYMENTS=gpt-4o-response-generation,anthropic:claude-sonnet-4-6@https://<foundry-resource>.services.ai.azure.com/anthropic
   ```

   Anthropic endpoints given this way must be the full base URL including the `/anthropic`
   path segment.

**Everything else is provider-agnostic and unchanged**: prompts, the query set, embeddings,
AI Search retrieval, `ChunkFormatterUtility`, `CitationProcessor`, all citation metrics
(`match`, `jsonBlockPresent`, verbosity, coverage, stacking) and the quality-comparison
stage. They operate on the response *text* and its `<FACT_MAP_JSON>` contract, which Claude
is asked to honour exactly like the GPT models.

## 2. Configuration

Set in `.env` (see `.env.sample`):

| Variable | Purpose |
|---|---|
| `ANTHROPIC_FOUNDRY_RESOURCE` **or** `ANTHROPIC_FOUNDRY_BASE_URL` | Exactly one (unless every Anthropic entry carries its own `@endpoint` suffix). The resource form expands to `https://<resource>.services.ai.azure.com/anthropic`. |
| `ANTHROPIC_FOUNDRY_API_KEY` | Optional. When unset, auth falls back to a **`DefaultAzureCredential` bearer token** (`az login`) — the same credential chain as the rest of the repo. |
| `LLM_MODEL_RESPONSE_MAX_TOKENS` | Sent as Anthropic's mandatory `max_tokens`. Shared with the answer + `<FACT_MAP_JSON>` block (and thinking, if enabled) — undersizing reproduces the gpt-5.1 truncation mode (`jsonBlockPresent=false`). |
| `HARNESS_ANTHROPIC_TEMPERATURE` | Optional single sampling parameter. Default: no sampling parameters sent (see §3.1). |
| `HARNESS_ANTHROPIC_THINKING=adaptive` | Optional. Default off — the Claude analogue of `LLM_REASONING_EFFORT=none`. |

Smoke run:

```bash
HARNESS_MAX_QUERIES=1 HARNESS_REPETITIONS=1 \
HARNESS_LLM_DEPLOYMENTS=gpt-4o-response-generation,anthropic:claude-sonnet-4-6 \
./run-harness.sh
```

## 3. Shortcomings and caveats to read the results with

### 3.1 The `top_p=0` determinism lever does not port
The earlier GPT-4o evaluation's single biggest reliability lever was `temperature=0` +
`top_p=0` constrained decoding. **Claude 4.x rejects that pair** (at most one sampling
parameter per request), so `AnthropicChatService` omits sampling entirely by default
(`HARNESS_ANTHROPIC_TEMPERATURE` can set temperature alone). The GPT-4o baseline keeps its
constrained decoding; Claude cannot replicate it — **some cross-model differences are
attributable to decoding configuration, not model quality**. Read repetition consistency
(the harness's existing multi-rep design) with this in mind.

### 3.2 Reasoning/thinking semantics differ
`LLM_REASONING_EFFORT` is an OpenAI reasoning-model knob and is ignored on the Anthropic
path. Claude's analogues are adaptive thinking (+ an `effort` setting). The default here is
thinking **off** — extract-and-cite needs no deep reasoning, and thinking tokens would share
the `max_tokens` budget (the same truncation risk §6 of the cross-model doc documents for
gpt-5.1). `HARNESS_ANTHROPIC_THINKING=adaptive` exists for a deliberate later experiment.

### 3.3 Auth
The Foundry backend supports an API key **or** a bearer token from the Azure credential
chain; the harness defaults to the bearer path (`az login`), consistent with the repo's
managed-identity norm. If a key is used, it lives only in the git-ignored `.env`.

### 3.4 Retry/timeout config does not apply
`AZURE_CLIENT_*` / `HTTP_CLIENT_*` env vars and `run-harness.sh`'s timeout flooring apply to
the Azure SDK clients only. The Anthropic SDK uses its own defaults (10-minute request
timeout, 2 retries with backoff on 429/5xx) — adequate for the harness.

### 3.5 Foundry prerequisite (outside this repo)
Claude Sonnet 4.6 must be **deployed in the Azure AI Foundry resource** first
(portal/marketplace step; region- and quota-dependent). Several Foundry features are beta,
but the plain Messages surface the harness needs is supported.

### 3.6 Judge bias
The quality-comparison judge is gpt-5.1. Cross-family judging (a GPT model scoring Claude
vs GPT outputs) can carry style bias — read verdicts comparatively and keep the manual
spot-check against the IDPC that the cross-model doc already mandates.

### 3.7 Prompt provenance
Prompts v1–v4 were tuned for GPT models — the all-caps `MUST`/`FORBIDDEN` emphasis was
chosen specifically to give `top_p=0` sharp single-token winners. Claude follows
instructions more literally and needs less coercive phrasing; behavioural differences under
the same prompt are **expected and are precisely what the harness measures**. A
Claude-tuned prompt variant would be a follow-up exercise, not a precondition.

### 3.8 Citation guard
`ResponseGenerationService` throws `CitationDegradedException` on citation-degraded answers;
the harness records those as ERROR cells. To inspect raw degraded Claude output instead, run
with `CITATION_GUARD_MODE=off`.

## 4. Route considered and declined: LiteLLM proxy

LiteLLM supports Claude-on-Foundry via its `azure_ai/` Azure Anthropic provider (API key or
Azure AD token) and would have allowed reusing the existing OpenAI-format clients with
near-zero Java changes — the `temperature+top_p` pair can even be dropped in proxy config
(`additional_drop_params`). It was declined for this exercise because it:

- inserts a **translation layer into the measurement path** (param mapping,
  `stop_reason`↔`finish_reason` mapping, its own retry behaviour) that production would not
  have unless a gateway is adopted platform-wide;
- adds **infrastructure to run and secure** (a proxy holding credentials for both backends)
  for what is a self-contained offline evaluation.

**Revisit if** a gateway becomes platform infrastructure or multi-provider routing becomes a
production requirement — then the evaluation should deliberately run *through* the gateway,
treating it as part of the system under test.

## 5. What was changed (this module only)

| File | Change |
|---|---|
| `pom.xml` | `com.anthropic:anthropic-java` + `anthropic-java-foundry` (harness-only) |
| `src/main/java/uk/gov/moj/cp/harness/AnthropicChatService.java` | New `ChatService` implementation (Messages API via `FoundryBackend`) |
| `src/main/java/uk/gov/moj/cp/harness/TestHarness.java` | `LlmConfig` carries a provider; `HARNESS_LLM_DEPLOYMENTS` accepts an optional `anthropic:` prefix; `buildService` routes accordingly |
| `.env.sample`, `run-harness.sh` | Foundry variables and knob documentation |

Reused unchanged: `ChatService` interface, `ResponseGenerationService`, `CitationProcessor`,
all metrics/report code, prompts, `user-queries.json`, `ResponseQualityComparator`.
