# System prompt evaluation — extending to the OpenAI SDK path

> Companion to `system-prompt-evaluation-cross-model.md`. That document evaluated the
> answer-retrieval prompt across GPT-4o and GPT-5.1 on the **Azure OpenAI SDK** path
> (`AzureChatService`, Chat Completions API). This note extends the exercise to the
> **OpenAI SDK** path (`OpenAiChatService`, Responses API), since the service can run
> either via `LLM_CHAT_SERVICE_PROVIDER` (`azure` | `openai`). Same prospective framing:
> the move to the GPT-5.1 reasoning model is **under evaluation, not in production**.

## 1. Why extend to the OpenAI SDK

The service has two interchangeable `ChatService` implementations, selected by
`LLM_CHAT_SERVICE_PROVIDER`:

| | `AzureChatService` (`azure`) | `OpenAiChatService` (`openai`) |
|---|---|---|
| SDK / API | `com.azure.ai.openai`, **Chat Completions** | `com.openai`, **Responses API** |
| Token cap | `max_completion_tokens` | `maxOutputTokens` |
| Reasoning-model control | `reasoning_effort` | `reasoning.effort` |
| Verbosity control | not available in the SDK | `text.verbosity` |
| Truncation signal | `finish_reason=length` / empty | `incompleteDetails` / empty |

The earlier evaluation only exercised the Azure path. Three of the four changes it
produced are **provider-agnostic** and already protect both paths:
- the improved prompt (`baseline-with-improvements`) — passed as the system instruction;
- the `CitationProcessor` hardening — post-processing, after the chat call;
- the `ChunkFormatterUtility` `CHUNK_ID` removal — input formatting, before the chat call.

The fourth — `reasoning_effort=none` — was **Azure-only**, so the OpenAI path was
unvalidated and parameterised differently. This note closes that gap.

## 2. Two defects found while wiring up the OpenAI path

1. **`reasoning_effort` was never set on the OpenAI path.** `OpenAiChatService` omitted
   `temperature`/`top_p` for reasoning models but applied no reasoning control, so gpt-5.1
   ran at the model-default effort — the very budget-exhaustion risk the Azure default of
   `none` was introduced to remove. **Fix:** added a configurable `LLM_REASONING_EFFORT`
   (default `none`; allowed `none|minimal|low|medium|high|xhigh`) applied via the Responses
   API `reasoning.effort`, mirroring `AzureChatService`.

2. **`verbosity=low` was sent to every model and gpt-4o rejected it.** The code applied
   `text.verbosity` unconditionally (a comment claimed it was "silently ignored by other
   models"). In practice gpt-4o returns `400: Unsupported value: 'low' is not supported with
   the 'gpt-4o-2024-11-20' model. Supported values are: 'medium'`, which failed **every**
   gpt-4o call on the OpenAI path. **Fix:** gated `verbosity` to reasoning models only
   (exactly as `temperature`/`top_p` are gated for non-reasoning models).

Both fixes are covered by `OpenAiChatServiceTest` (reasoning-effort default `none` and
verbosity present for reasoning models / absent for non-reasoning).

## 3. The run (OpenAI SDK) vs the earlier run (Azure SDK)

Both: 2 reps × 2 prompts (`baseline-production`, `baseline-with-improvements`) × 2 models
(`gpt-4o`, `gpt-5.1`) × 10 queries; `reasoning_effort=none`; `max_completion_tokens=7000`.

| Dimension | Azure SDK | OpenAI SDK |
|---|---|---|
| Calls generated (`ok`) | 80/80 | 80/80 |
| Empty / reasoning-exhaustion failures | 0 | 0 |
| gpt-5.1 + improved prompt — citations (`match`, 6 findings queries) | **6/6 clean (2/2 each)** | **6/6 clean (2/2 each)** |
| gpt-4o — citations | messy (model trait) | messy (slightly worse) |
| GUID-derived / hex citationIds | none | none |
| gpt-5.1 clean-JSON misses on the long `Chronology` | 2 reps output-truncated (`finish_reason=length`) | 1 rep catastrophic counter-loop (`[1]…[2612]`, no JSON block) |

gpt-5.1 + improved prompt — verbosity & coverage (mean over the 5 substantive findings queries):

| | Azure SDK | OpenAI SDK |
|---|---|---|
| answer words | ~2,078 | ~2,140 |
| distinct citations | ~18.6 | ~20.0 |

Per-query words (Azure → OpenAI): Summary 2282→1615, Applications 378→261, **Chronology
2623→3338**, Prosecution 2553→2728, Witnesses 2556→2757.

## 4. Outcome

1. **Both SDK paths now work for the gpt-5.1 migration** — but only after the two defects
   above were fixed. Before the fixes the OpenAI path had no reasoning control and failed
   every gpt-4o call.
2. **Citation quality is equivalent and good on both SDKs.** The improved prompt on gpt-5.1
   is 100% clean (`match` 2/2 across all six findings queries) on both. gpt-4o remains the
   messier model on both (marginally worse on the OpenAI Responses path). No GUID-derived ids
   on either — the OpenAI run's only large ids were a single sequential counter-loop, which
   the `CitationProcessor` catastrophic-bracket guard strips before it reaches a user.
3. **`verbosity=low` is *not* a conciseness win at scale.** A single-query smoke suggested the
   OpenAI path was much shorter, but across the full set the mean word counts are essentially
   equal (~2,078 vs ~2,140) and the per-query effect is mixed (Summary shorter, Chronology
   longer). gpt-5.1's length is driven by citation coverage, not the verbosity knob.
4. **Reliability is comparable, with the same weak spot.** The long `Chronology` query is where
   gpt-5.1 occasionally fails to emit a clean citation block — Azure via output-token
   truncation, OpenAI via a counter-loop. Different mechanism, same locus; the improved prompt
   avoids it on both (truncation-free on gpt-5.1), and the `CitationProcessor` guard degrades
   the rare failure gracefully.

**Net:** the prompt/citation improvements are SDK-agnostic and hold on both paths. The
reliability/length levers are now at parity — `reasoning_effort=none` on both, `verbosity`
correctly scoped to reasoning models on the OpenAI path. **Choosing the OpenAI vs Azure SDK is
a reliability/operations decision, not a citation-quality one, and verbosity is not a deciding
factor.**

## 5. Configuration parity (both providers, reasoning models)

| Env var | `azure` / `AzureChatService` | `openai` / `OpenAiChatService` |
|---|---|---|
| `LLM_REASONING_EFFORT` | applied (default `none`) | **now applied (default `none`)** |
| `LLM_MODEL_RESPONSE_VERBOSITY` | n/a (SDK lacks it) | applied to reasoning models only (`low|medium|high`) |
| `LLM_MODEL_RESPONSE_MAX_TOKENS` | `max_completion_tokens` | `maxOutputTokens` |

> Re-run on either path via the harness: set `LLM_CHAT_SERVICE_PROVIDER` in `.env` and run
> `./run-harness.sh`.
