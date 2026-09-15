# Content-filter parity spike — findings (Stage 0, OAI-03)

| | |
|---|---|
| **Date** | 2026-09-15 |
| **Jira** | DD-43422 (parent: DD-43417 — OpenAI SDK migration) |
| **Environment** | Non-live STE Azure OpenAI resource `open-ai-ste-c3vx` (`RG-STE-AI-01`, subscription "Strategic Platform - non-live") |
| **Deployments tested** | `gpt-4o-response-generation` (gpt-4o 2024-11-20), `gpt-5.1` |
| **Surface under test** | `{endpoint}/openai/v1` Responses API via openai-java 4.41.0 (`OpenAiClientFactory`, bearer token / DefaultAzureCredential) |
| **Method** | Spike tool `ai-document-system-prompt-harness-eval/src/main/java/uk/gov/moj/cp/harness/ContentFilterSpikeTool.java`, run via `mvn -pl ai-document-system-prompt-harness-eval exec:java -Dharness.mainClass=uk.gov.moj.cp.harness.ContentFilterSpikeTool` with the module's `.env` exported (mirrors `run-harness.sh`) |

The deliberately-filtered probe input is never reproduced in this note or in the tool's
output — only its SHA-256 (`99ecc04002…9961769a`) and category (violence: a request for a
graphic violent description) are recorded, per AC-22 (no prompt/query/document content in
diagnostics).

---

## 1. What the Azure path logs today

`ai-document-shared-artefacts/src/main/java/uk/gov/moj/cp/ai/service/AzureChatService.java`:

- **Output-side filter trip** (HTTP 200, `finish_reason=content_filter`): lines 128–129 WARN
  "LLM produced filtered response" with an explanation built by
  `generateExplanationForEmptyResponse` (lines 184–208), which serialises
  `chatCompletions.getPromptFilterResults()` (input-side per-category `{filtered, severity}`,
  lines 189–194) and `chatChoice.getContentFilterResults()` (output-side, lines 196–200) into
  the WARN text.
- **Token-limit truncation** (`finish_reason=length`): lines 130–131 WARN "token limit was
  reached".
- **Input-side filter trip** (HTTP **400** `content_filter` from Azure): **not handled by this
  code at all.** The Azure SDK throws `com.azure.core.exception.HttpResponseException` from
  `getChatCompletions` (line 116); `callModel` catches only `JsonProcessingException`, so the
  400 propagates as an unlogged runtime exception. The celebrated `getPromptFilterResults()`
  diagnostics only ever fire on the 200/`content_filter` (output-side) branch. Any parity
  requirement must be stated against this actual behaviour, not an idealised one.

## 2. Evidence per probe

### Probe 1 — input-side filter trip (`client.responses().create`)

**Not provocable on this resource.** The violence-category trip prompt returned **200
`completed`** on both `gpt-4o-response-generation` and `gpt-5.1` (3.8 s / 2.7 s, model answered).
Root cause, from the control plane (`az cognitiveservices account deployment list`): every chat
deployment on `open-ai-ste-c3vx` pins the custom RAI policy **`DisableFilter`** (base
`Microsoft.Default`) — content filtering is disabled resource-wide:

```
Name                           Rai
-----------------------------  -------------------
text-embedding-3-large         Microsoft.DefaultV2
gpt-4o-judge                   DisableFilter
gpt-4o-response-generation     DisableFilter
Llama-3.3-70B-Instruct-Mahesh  DisableFilter
gpt-5.1                        DisableFilter
```

Creating a temporary deployment pinned to `Microsoft.DefaultV2` (to observe a real
`content_filter` 400) was attempted and **blocked by the local permission policy** (infra
mutation); reading the `DisableFilter` policy body was likewise blocked. A follow-up needing
~5 minutes with portal/CLI access can close this: create `gpt-4o-cf-spike` with
`--rai-policy-name Microsoft.DefaultV2`, re-run the spike tool
(`SPIKE_PRIMARY_DEPLOYMENT=gpt-4o-cf-spike`, secondary blank), delete the deployment.

The 200 responses did, however, carry Azure's filter **annotation block** (see probe 4) with
`blocked: false` and empty per-category results — consistent with a disabled filter:

```json
"content_filters": [ {
  "blocked": false,
  "source_type": "completion",
  "content_filter_raw": [],
  "content_filter_results": {},
  "content_filter_offsets": { "start_offset": 0, "end_offset": 215, "check_offset": 0 }
} ]
```

### Probe 1b — error-body passthrough on a 400 (substitute mechanism evidence)

Since a filter 400 cannot be provoked here, a deterministic parameter-validation 400
(`temperature: 5.0`) was used to observe how openai-java surfaces an Azure 400 on this exact
surface:

```
exception class = com.openai.errors.BadRequestException
statusCode()    = 400
code()          = decimal_above_max_value
type()          = invalid_request_error
param()         = temperature
body() JSON:
{
  "code"    : "decimal_above_max_value",
  "message" : "Invalid 'temperature': decimal above maximum value. Expected a value <= 2.0, but got 5.0 instead.",
  "param"   : "temperature",
  "type"    : "invalid_request_error"
}
```

Mechanism confirmed: `OpenAIServiceException.body()` returns the wire response's parsed
`error` object as a schemaless `JsonValue` — **every member of the error object is preserved
verbatim**, not just the four typed accessors. A `content_filter` 400 (documented Azure error
contract: `error.code = "content_filter"`, `error.innererror.code =
"ResponsibleAIPolicyViolation"`, `error.innererror.content_filter_result` = per-category
`{filtered, severity}`) would therefore surface its `innererror.content_filter_result` inside
`body()` — *documented-but-not-directly-observed* on this resource; the passthrough mechanism
itself is observed.

**Retry behaviour (fail-fast) confirmed:** the 400 failed once in **140 ms** (a retried call
would show the SDK's exponential backoff, ≥ seconds). Statically,
`com.openai.core.http.RetryingHttpClient.shouldRetry` (openai-java-core 4.41.0, verified by
bytecode inspection) retries only on `X-Should-Retry: true`, 408, 409, 429 and ≥ 500 — a 400
is never retried in-process.

### Probe 2 — output truncation signal

Benign prompt, `max_output_tokens = 16` (Responses API minimum):

```
response.status()            = incomplete
response.incompleteDetails() = reason=max_output_tokens
```

Truncation is cleanly distinguishable via `incomplete_details.reason = max_output_tokens` —
the Responses-API equivalent of `finish_reason=length` (`TOKEN_LIMIT_REACHED`).

### Probe 3 — output-side filter trip (best effort)

**Not provoked** — with filtering disabled resource-wide (probe 1), no output-side trip is
possible either, and probe content was deliberately not escalated. Per the Responses API
contract the signal would be `status = incomplete` + `incomplete_details.reason =
content_filter` (the enum value exists in the SDK: `Response.IncompleteDetails.Reason`),
plus `content_filters[].blocked = true` in the annotation block. To be verified in the
follow-up run against a filtered deployment.

### Probe 4 — filter annotations on a successful response

**Explicit answer: YES — an Azure filter annotation block appears on successful `/openai/v1`
Responses-API responses.** It is not the chat-completions-era `prompt_filter_results` /
`choices[].content_filter_results`; it is a top-level **`content_filters`** array (per output
segment: `blocked`, `source_type`, `content_filter_results` per-category map,
`content_filter_offsets`). Observed on the benign call's raw body (ids redacted):

```json
{
  "id": "resp_<redacted>",
  "object": "response",
  "status": "completed",
  "content_filters": [ {
    "blocked": false,
    "source_type": "completion",
    "content_filter_raw": [],
    "content_filter_results": {},
    "content_filter_offsets": { "start_offset": 0, "end_offset": 35, "check_offset": 0 }
  } ],
  "incomplete_details": null,
  "...": "…"
}
```

openai-java does not model this Azure extension, but it is **not dropped**: it round-trips into
`response._additionalProperties().get("content_filters")` on the plain (non-raw) client — no
`withRawResponse()` needed. Under `DisableFilter` the per-category `content_filter_results` map
is empty; on a filtered deployment it is where per-category `{filtered, severity}` annotations
would appear (to be confirmed in the follow-up run). Only `source_type: "completion"` entries
were observed; whether a `"prompt"`-side entry appears under a filtering policy is likewise
follow-up material.

### Probe 5 — Azure-SDK side-by-side

Same trip prompt through `AzureChatService` (Azure SDK, chat-completions surface, preview
api-version) on `gpt-4o-response-generation`: **no auth failure** (the known preview-api-version
401 did not occur on this resource) and **no filter trip** — `finish_reason=stop`, 200 with a
241-char answer in 2.5 s. Consistent with probe 1: the filter is disabled at the resource, so
the Azure path cannot demonstrate its diagnostics here either. The two SDK paths behave
identically on this resource: both deliver the model's answer un-filtered.

## 3. Async-worker interaction (unchanged by the SDK choice)

A deterministically-filtered prompt produces the same failure on every delivery, so on the
async path the resulting exception (Azure SDK `HttpResponseException` today, openai-java
`BadRequestException` after migration) rides queue redelivery up to `maxDequeueCount` **3**
(`ai-document-answer-retrieval-function/host.json` line 16), burning all attempts before the
worker records the FAILED outcome. This matches the existing behaviour for the citation guard
(`CITATION_GUARD_MODE` — CLAUDE.md "Citation guard": each retry is a fresh short invocation;
at exhaustion the configured deliver/reject policy applies). The SDK migration does not change
this dynamic — only what is *loggable* on each failed attempt.

## 4. VERDICT

**Parity achievable — gap confirmed: `OpenAiChatService` currently logs no content-filter
diagnostics on either failure mode; FR-11 logging changes are required in `OpenAiChatService`
(and nowhere else).** Specifically:

What **IS** recoverable through openai-java on `/openai/v1`:

1. **Input-side trip (400):** catch `com.openai.errors.OpenAIServiceException`
   (concretely `BadRequestException`) and log `statusCode()`, `code()`, `type()`, `param()`
   and the `innererror.content_filter_result` subtree of `body()` — the error body is passed
   through verbatim as a schemaless `JsonValue` (observed for a non-filter 400; the
   `content_filter_result` member is per Azure's documented error contract and needs one
   confirmation run against a filtered deployment). This is **better** than current
   `AzureChatService` behaviour, which lets the input-side 400 propagate unlogged.
2. **Output-side trip (200/incomplete):** `response.incompleteDetails().reason()` distinguishes
   `content_filter` from `max_output_tokens` (the latter observed working), replacing the
   `CONTENT_FILTERED` / `TOKEN_LIMIT_REACHED` finish-reason branch.
3. **Per-category severities on responses:** the Azure `content_filters` annotation block
   (`blocked`, `source_type`, per-category `content_filter_results`) survives into
   `response._additionalProperties().get("content_filters")` — the functional equivalent of
   `getPromptFilterResults()`/`getContentFilterResults()`, available without a raw-response
   client.

What is **NOT** (yet) directly demonstrated:

- An actual `content_filter` 400 body and a `blocked: true` / `reason=content_filter` response
  on this surface — not provocable on `open-ai-ste-c3vx` because every chat deployment pins the
  `DisableFilter` RAI policy, and creating a temporarily-filtered deployment was outside this
  spike's permissions. One follow-up run of the existing spike tool against a
  `Microsoft.DefaultV2` deployment closes both.

## 5. Recommended FR-11 change sketch (for the migration story, NOT implemented in this spike)

In `OpenAiChatService.callModel`:

1. **Catch `OpenAIServiceException`** around `client.responses().create(...)`; when
   `code() == "content_filter"` (or `body()` contains `innererror.content_filter_result`),
   WARN-log: status code, `code()`, `type()`, `param()` and the serialised
   `innererror.content_filter_result` JSON — then rethrow (preserving async redelivery
   semantics). No prompt/query/document content appears in any of these fields (AC-22 safe:
   they are category/severity metadata only — verified shape in probe 1b).
2. **Branch on `incompleteDetails().reason()`** where today it only logs a generic
   "incomplete" WARN: `content_filter` → the CONTENT_FILTERED-equivalent WARN, additionally
   serialising `_additionalProperties().get("content_filters")` (category/severity metadata
   only); `max_output_tokens` → the TOKEN_LIMIT_REACHED-equivalent WARN (matching
   `AzureChatService` lines 128–134).
3. Optionally include the `content_filters` annotation summary (e.g. `blocked` flags) at DEBUG
   on successful responses; not required for parity (the Azure path logs annotations only on
   the filtered branch).

## 6. Artefacts

- Spike tool (reusable; recommend keeping in the harness module):
  `ai-document-system-prompt-harness-eval/src/main/java/uk/gov/moj/cp/harness/ContentFilterSpikeTool.java`
- Run command: see header table (env: optional `SPIKE_PRIMARY_DEPLOYMENT` /
  `SPIKE_SECONDARY_DEPLOYMENT`).
- Nothing committed; no production code touched.
