# Citation guard — JIRA summary

## Issue observed

A small proportion (~1–3%) of substantive LLM answers were reaching users with citations
partially or entirely missing. Analysis across evaluation-harness runs showed the model almost
never *writes* an answer without attempting citations (0 of 117 substantive answers); the loss
happens in post-processing when the model's citation JSON is defective — either the
`FACT_MAP_JSON` block is absent (observed on a long GPT-5.1 answer: every marker stripped, fully
uncited 3,400-word answer delivered) or the block collapses to fewer entries than inline markers
(observed on GPT-4o: 31 of 32 markers stripped). The `CitationProcessor` correctly strips the
orphaned `[N]` markers for readability, but the degradation was log-only — the answer still
progressed as `ANSWER_GENERATED` and was sent for scoring.

## Implementation

- `CitationProcessor` now returns a `CitationOutcome` (block present, empty fact-map,
  inline/rendered/stripped marker counts) alongside the formatted text, surfacing the previously
  discarded degradation signal.
- A **citation guard** in `ResponseGenerationService` (the single choke-point for both the sync
  HTTP and async queue paths) evaluates each answer once: it progresses only if it carries at
  least one rendered citation, or is a deliberate no-evidence refusal (empty `FACT_MAP_JSON[]`,
  no markers). Anything else throws a `CitationDegradedException` carrying the degraded answer —
  there is deliberately **no in-process retry loop**, so a single queue-message execution never
  becomes a long-running transaction.
- **Async retry rides the queue redelivery mechanism**: the exception propagates, the message is
  redelivered (a fresh short invocation — re-embed, re-search, one LLM call) up to the queue's
  `maxDequeueCount` (3). These failures are non-deterministic and typically succeed on a
  redelivery. The **sync path performs no retries** — the policy applies immediately and the
  caller can simply re-submit.
- Exhaustion policy via `CITATION_GUARD_MODE` (env-mapped enum): **`deliver` (default)** — the
  degraded answer is delivered and scored, with the guard reason recorded in the async status
  table's reason column and logs; `reject` — the request fails with the reason and the uncited
  text is never delivered (scoring skipped); `off` — guard disabled. No API-contract change
  (existing statuses reused; HTTP stays 200).
- Evaluation harness extended with matching metrics (`rendered`, `stripped`,
  `uncitedSubstantive`) so the failure class is tracked per run.

## Expected outcome

Citation-degraded answers no longer progress silently. On the async path, at the measured ~1–3%
incidence, queue redelivery (up to 3 short attempts with natural backoff) is expected to resolve
nearly all cases; a persistently degraded answer is then delivered with the reason recorded
(default) or rejected (`reject` mode) — either way observable, never silent. On the sync path the
policy applies immediately with no added latency. Legitimate "no relevant evidence" refusals are
unaffected. Covered by unit tests across the service and both function paths (full module suite
passing); rollback is config-only (`CITATION_GUARD_MODE=off`).
