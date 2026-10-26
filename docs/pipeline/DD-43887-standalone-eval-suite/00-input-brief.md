# DD-43887 — Standalone end-to-end evaluation suite for cp-ai-rag-service

## Problem

The RAG service is domain-agnostic, but its answer quality is only evaluated end to end
through a client (CDK → ragEval in `cpp-ui-e2e-serenity`). That suite tests the client's
prompts and documents. Nothing at service level proves that the core capabilities
(ingest, retrieve, cite, refuse) work end to end and stay that way between releases.

The existing suites cover neighbouring ground but not this:

| Suite | What it proves | Why it isn't enough |
|---|---|---|
| `ai-service-orchestration-test` (`OrchestrationIT`, `NegativePathIT`, `ClientIsolationIT`) | The pipeline runs, contracts hold, clients are isolated, idempotency works | Asserts that an answer exists, not that it is right |
| `ai-document-system-prompt-harness-eval` | Compares prompts and models offline | Runs the pipeline in-process, not through the API; uses client documents; not a gate |
| ragEval (Serenity, via CDK) | Quality for one client's use case | Client-owned data and prompts; tests the client, not the service |

## Agreed direction (discussion, 2026-10-26)

- Drive the service only through its public HTTP API.
- Use a synthetic, domain-neutral golden dataset owned by this repo. Each document targets
  one behaviour, with facts planted at known pages.
- Measure each stage separately (ingestion, retrieval, answer, citations, operations), so a
  failure shows where it happened.
- Deterministic measures gate. LLM-judged groundedness is report-only. The 2026-10-15 ragas
  pilot found faithfulness the only LLM-judged metric adding new signal, and too noisy to
  gate on.
- Repeat runs, a baseline and a known-issues register, because LLM output varies.
- Client-specific quality stays in ragEval. No API contract change.

## Acceptance criteria (ticket)

- Design doc agreed (this folder).
- First version of the golden dataset and item format.
- Suite runs as a profile alongside `ai-service-orchestration-test`, with deterministic gates.
