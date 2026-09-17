# User Stories: OpenAI SDK Migration (CP AI RAG Service)

> Stage 3 artefact (story-writer). Source: `01-requirements.md`, `02-design.md`, `00-input-brief.md`.

## Jira mapping

Parent ticket: [DD-43417](https://hmcts.atlassian.net/browse/DD-43417) — "Migrate off the Azure OpenAI SDK onto the official OpenAI Java SDK". Each story below is one Jira sub-task under the parent, one per delivery stage, one PR each (D1 — staged delivery constraint). In Jira text, story IDs are written hyphen-free (`OAI01`) to avoid Jira auto-linking pseudo issue keys.

| Story | Stage | Jira subtask |
|---|---|---|
| OAI-01 | Stage 1 — OpenAI client hardening | DD-43420 |
| OAI-02 | Stage 2 — Embeddings provider switch | DD-43421 |
| OAI-03 | Stage 0 — Content-filter diagnostics parity spike | DD-43422 |
| OAI-04 | Stage 3 — Cut-over | DD-43423 |
| OAI-05 | Stage 4 — Deferred removal (not scheduled) | DD-43424 |

**ADR gate:** the design's Follow-ups section records "ADR recommended: yes" — *"Model access via the GA OpenAI Java SDK on the Azure `/openai/v1` passthrough surface, behind per-capability provider toggles."* It must capture design decisions DD-1…DD-13, the accepted capability losses (non-configurable backoff; content-filter annotations pending the OAI-03 verdict) and the one-way door (Stage 4 deletion, OAI-05). Store it under `docs/pipeline/adrs/` and get tech-lead sign-off **before OAI-01 starts** — it is a merge gate for the whole story set, exactly as the DD-42722 initiative required for its multi-client ADR.

**Cross-cutting rules:**
- Every stage PR is independently mergeable and leaves `main` in a working, releasable state (D1). Stages 1 and 2 are behaviour-neutral at their default provider setting (NFR-2, NFR-10) — no coordinated app-settings change is required to merge them.
- The Jira ticket reference goes in the PR description (`**Jira:** DD-43417`), not the PR title (repo convention).
- OAI-03 (Stage 0) runs **in parallel** with OAI-01/OAI-02 and has no code dependency on them; its verdict is a **gate**, not an input, on OAI-05.
- OAI-05 is written now for scoping purposes only. It must **not** be pulled into a sprint until OQ-6 ("fully exercised in production") is defined and satisfied (D4). ~~and (b) the OAI-03 verdict is recorded~~ **The D3 gate is closed: OAI-03 delivered findings-only (PR #141), and the stakeholder decision (Mahesh, 2026-09-15) — content filtering is guaranteed disabled on all model resources, no FR-11 logging required — is recorded in `04-content-filter-parity-findings.md` §4.**

---

## Summary table

| Story ID | Title | Stage | FRs | ACs | Dependencies | Can run in parallel with |
|---|---|---|---|---|---|---|
| OAI-01 | OpenAI client retry/timeout hardening | Stage 1 | FR-1, FR-2, FR-3 | AC-1…AC-6 | ADR accepted | OAI-02, OAI-03 |
| OAI-02 | Embeddings provider switch (default `azure`, zero behaviour change) | Stage 2 | FR-4…FR-9 | AC-7…AC-19 | ADR accepted; benefits from, but does not require, OAI-01 (shares `OpenAiClientFactory`) | OAI-01, OAI-03 |
| OAI-03 | Content-filter diagnostics parity spike (+ FR-11 logging if a gap is confirmed) | Stage 0 | FR-10, FR-11 | AC-20…AC-22 | None (offline harness only) | OAI-01, OAI-02 |
| OAI-04 | Cut-over — flip defaults to `openai`, harness move, `PinnedApiVersionEmbeddingService` deleted, both legs proven | Stage 3 | FR-12, FR-13, FR-14 | AC-23…AC-27 | OAI-01, OAI-02 merged | — (sequential; needs both prior stages) |
| OAI-05 | **[DEFERRED — not scheduled]** Removal of the Azure OpenAI SDK | Stage 4 | FR-15, FR-16, FR-17 | AC-28…AC-30 | OAI-04 merged and baked in production (OQ-6) **and** OAI-03 verdict closed (D3) | — (last, gated) |

---

## OAI-01: OpenAI client retry/timeout hardening

### User story
As a **developer/maintainer of the CP AI RAG Service**,
I want **the `OpenAiClientFactory`-built client to apply explicit, environment-driven `maxRetries` and per-phase `Timeout` configuration instead of relying on openai-java's SDK defaults**,
so that **the OpenAI SDK path has the same explicitly-tuned resilience posture as the Azure SDK path, with no silent reliance on undocumented defaults that could under- or over-run the Functions host timeout**.

### Background
FR-1, FR-2, FR-3; AC-1…AC-6. New sibling class `uk.gov.moj.cp.ai.client.config.OpenAiClientConfiguration` alongside the existing `ClientConfiguration`, wired into `OpenAiClientFactory.getInstance(endpoint)` via two additional builder calls (`.maxRetries(...)`, `.timeout(...)`). The existing bearer-token supplier, `baseUrl(endpoint + "/openai/v1")` and per-endpoint `ConcurrentHashMap` cache are untouched.

Resolves three open questions from `01-requirements.md`, all recorded as design decisions: **OQ-1** — reuse `AZURE_CLIENT_MAX_RETRIES` / `HTTP_CLIENT_*_TIMEOUT_IN_SECONDS` rather than introducing `OPENAI_CLIENT_*` variables (DD-4); **OQ-2** — default `maxRetries` to `3`, matching `ClientConfiguration`, not the SDK's default of `2` (DD-5); **OQ-3** — `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` (default 180 s) maps to `Timeout.request` and sits safely inside the 30-minute hosting-plan `functionTimeout` default and, for the realistic stall case, inside the 300 s idempotency lease TTL (DD-6). openai-java's exponential backoff curve (fixed 0.5 s → 8 s) is **not configurable** — `AZURE_CLIENT_BASE_DELAY_IN_SECONDS`/`AZURE_CLIENT_MAX_DELAY_IN_SECONDS` become a documented no-op on this client, not a silent gap.

### Acceptance criteria
Delivers AC-1 through AC-6 in `01-requirements.md` verbatim (client `maxRetries`/`Timeout` wiring, documented-default logging, cache/log-line invariants preserved, `BearerTokenCredential`/`/openai/v1` unchanged, base/max-delay vars proven to be a harmless no-op). Story-specific sharpening:
- [ ] The single INFO line logging effective `maxRetries` + four timeout values (AC-3) is emitted once per endpoint from inside the caching `computeIfAbsent` lambda, not on every `getInstance` call (ties AC-3 to AC-4).
- [ ] Class Javadoc on `OpenAiClientConfiguration` states explicitly, per FR-3, that base/max delay do not apply to this client (not just a code comment).

### NFR links
- NFR-5 (Resilience): retry count and per-phase timeouts explicitly configured from env vars; effective request timeout stays below the Functions host `functionTimeout`.
- NFR-6 (Observability): provider/effective-config logged once at construction; no PII/prompt content.
- NFR-7 (Test coverage): new `OpenAiClientConfigurationTest`; extended `OpenAiClientFactoryTest` (5 existing cases retained).
- NFR-9 (Maintainability): the accepted capability loss (non-configurable backoff) is documented, not silently dropped.

### Out of scope for this story
- Any change to the embeddings interface/implementations (OAI-02).
- The content-filter diagnostics spike (OAI-03) — independent of client-level retry/timeout config.
- Flipping any provider default (OAI-04) or removing the Azure SDK (OAI-05).
- The optional `OPENAI_CLIENT_*` rename with legacy fallback (deferred to Stage 4, OQ-1).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `OpenAiClientConfiguration` added to `ai-document-shared-artefacts` (`uk.gov.moj.cp.ai.client.config`); `OpenAiClientFactory` wired to apply it (`.maxRetries(...)`, `.timeout(...)`).
- [ ] New `OpenAiClientConfigurationTest` (env → `maxRetries`/`Timeout` phases, documented defaults, base/max-delay no-op case) and extended `OpenAiClientFactoryTest` (caching/log-line invariant, config-applied spy assertion, `/openai/v1` + bearer credential unchanged).
- [ ] `mvn test -pl ai-document-shared-artefacts` passes; `mvn verify` green (Surefire + JaCoCo) with coverage on all new classes.
- [ ] SonarQube quality gate passes on the Azure DevOps CI PR build.
- [ ] Root `CLAUDE.md` "Key environment variables" section updated with the FR-3 backoff-not-configurable note for the OpenAI client path.
- [ ] No integration-test change required (behaviour-neutral for the unchanged `azure` chat/embeddings defaults) — confirmed by the existing `ai-service-orchestration-test` suite running unchanged and green.
- [ ] PR description references `DD-43417` and links the accepted ADR (per the initiative-level ADR gate).

### Notes / open questions
- OQ-1, OQ-2, OQ-3 are resolved by design recommendation (DD-4, DD-5, DD-6) — no further decision needed before implementation.
- Two follow-up tickets identified by the design (pin `functionTimeout` explicitly in the queue-worker `host.json` files; reconcile the retry-chain wall-clock ceiling with `IDEMPOTENCY_LEASE_TTL_SECONDS`) are **out of scope for this story** — they are pre-existing exposure on the Azure path too, not introduced here, and should be raised as separate tickets rather than folded in.

---

## OAI-02: Embeddings provider switch — default `azure`, zero behaviour change

### User story
As a **developer/maintainer**,
I want **an `EmbeddingService` interface with `AzureEmbeddingService` (verbatim rename) and a new `OpenAiEmbeddingService` implementation, selected via a new `EMBEDDING_SERVICE_PROVIDER` env var (default `azure`) mirroring the existing `ChatServiceFactory` pattern**,
so that **embeddings become provider-switchable exactly like chat already is, with the answer-retrieval and ingestion functions unaffected — byte-for-byte identical behaviour — until the toggle is deliberately flipped**.

### Background
FR-4…FR-9; AC-7…AC-19. Mirrors the existing chat provider-switch shape line-for-line (DD-2): `EmbeddingServiceFactory` mirrors `ChatServiceFactory`'s defaulting/normalisation/error-message conventions, and `EmbeddingService` becomes the interface name in the existing package with implementations renamed (DD-3), so no consumer signature, import or mock changes. `OpenAiEmbeddingService` reproduces the Azure implementation's observable semantics: `cp-ai-document-rag-embedding-service` user tag, results explicitly re-sorted by `Embedding::index` to restore input order (DD-7 — the one failure mode that could silently corrupt the AI Search index via `ChunkEmbeddingService.enrichChunksWithEmbeddings`'s positional association), empty/absent data → WARN + empty list, all SDK exceptions wrapped in `EmbeddingServiceException`. `EmbedDataService` and `ChunkEmbeddingService` are wired through the factory; `RagHarness.setupEnvVarMap()` forwards both provider vars so integration-test hosts can run either leg. Two pre-existing documentation gaps (`LLM_CHAT_SERVICE_PROVIDER` undocumented in any function's sample settings; `HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS` in no sample) are closed alongside the new variable (FR-9).

Resolves **OQ-7** — keep `EmbeddingServiceTest` as-is, renamed `AzureEmbeddingServiceTest`, on its internal-API (`DefaultJsonReader`) fixtures, since the class it tests is deleted wholesale at Stage 4 (DD-9); and **OQ-8** — keep the `.user(...)` tag on the v1 surface, verified with one real call in this PR, dropped and documented as a gap only if rejected (DD-13).

### Acceptance criteria
Delivers AC-7 through AC-19 in `01-requirements.md` verbatim (interface extraction with no consumer signature change; ordering/empty-data/exception-wrapping/null-input parity on `OpenAiEmbeddingService`; factory defaulting/case-insensitivity/unknown-value rejection; both consumers wired through the factory with batching semantics unchanged; harness provider vars forwarded; documentation surface updated). Story-specific sharpening:
- [ ] AC-12's `user` tag is verified live against a real v1 deployment (OQ-8) before merge; if rejected, the field is dropped, the gap is recorded in the PR description **and** carried into the OAI-03 findings note (it shares the same "diagnostic/behavioural gap" ledger).
- [ ] The AC-8 ordering test deliberately shuffles `Embedding.index()` values in the fixture response — asserting the code sorts, not that it happens to preserve array order.

### NFR links
- NFR-1 (Functional parity): same vector dimensionality, `List<Float>` type, and ordering as the Azure path for the same input/deployment.
- NFR-10 (Backward compatibility): zero-behaviour-change refactor at the `azure` default — byte-for-byte behavioural parity, proven by the unchanged existing integration suite staying green.
- NFR-7 (Test coverage): new `OpenAiEmbeddingServiceTest`, `EmbeddingServiceFactoryTest`; renamed `AzureEmbeddingServiceTest` unchanged; all existing consumer tests (`EmbedDataServiceTest`, `ChunkEmbeddingServiceTest`, `ChunkEmbeddingServiceClientIdentityTest`) pass unmodified.

### Out of scope for this story
- Flipping `EMBEDDING_SERVICE_PROVIDER`'s default to `openai` (OAI-04).
- Moving the eval harness to `OpenAiEmbeddingService` or deleting `PinnedApiVersionEmbeddingService` — it transitionally becomes `extends AzureEmbeddingService` here (DD-8) and is only deleted at Stage 3 (OAI-04).
- The content-filter diagnostics spike (OAI-03) — chat-side only, unrelated to embeddings.
- Rewriting `EmbeddingServiceTest`'s internal-API fixtures onto public builders (OQ-7 — explicitly rejected by design as uneconomic given the scheduled Stage 4 deletion).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `EmbeddingService` becomes an interface; `AzureEmbeddingService` (verbatim rename) and `OpenAiEmbeddingService` (new) both implement it; `EmbeddingServiceFactory` added; `SharedSystemVariables` gains `EMBEDDING_SERVICE_PROVIDER` (default `azure`); `EmbedDataService` and `ChunkEmbeddingService` construct via the factory; `PinnedApiVersionEmbeddingService` changed to `extends AzureEmbeddingService`.
- [ ] `RagHarness.setupEnvVarMap()` forwards both `LLM_CHAT_SERVICE_PROVIDER` and `EMBEDDING_SERVICE_PROVIDER` with `azure` defaults.
- [ ] New/renamed unit tests as listed under NFR links; `mvn test` passes for `ai-document-shared-artefacts`, `ai-document-answer-retrieval-function`, `ai-document-ingestion-function`, `ai-document-system-prompt-harness-eval`.
- [ ] `mvn verify` green (Surefire + JaCoCo) with coverage on all new/changed classes.
- [ ] SonarQube quality gate passes on the Azure DevOps CI PR build.
- [ ] Existing `ai-service-orchestration-test` suite re-run unchanged and green on the unmodified `azure` default (the NFR-10 evidence) — no new integration tests required this stage.
- [ ] Documentation updated: `ai-document-answer-retrieval-function/Azure/local.settings.sample.json` (`EMBEDDING_SERVICE_PROVIDER`, plus the previously-missing `LLM_CHAT_SERVICE_PROVIDER` and `HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS`), `ai-document-ingestion-function/Azure/local.settings.sample.json` (`EMBEDDING_SERVICE_PROVIDER`, `HTTP_CLIENT_WRITE_TIMEOUT_IN_SECONDS`), `ai-document-answer-scoring-function/Azure/local.settings.sample.json` (`LLM_CHAT_SERVICE_PROVIDER`), `ai-document-system-prompt-harness-eval/.env.sample` + `README.md`, `ai-service-orchestration-test/.env.sample`, and root `CLAUDE.md`'s "Key environment variables" section (both toggles, accepted values, default, and a pointer to the upcoming Stage 3 default flip).
- [ ] PR description references `DD-43417` and records the OQ-8 `user`-tag verification outcome.

### Notes / open questions
- OQ-7 resolved by design (DD-9): keep `EmbeddingServiceTest`, renamed `AzureEmbeddingServiceTest`, unchanged.
- OQ-8 (embedding `user` tag on `/openai/v1`) requires one live-call verification as part of this PR, not before it — see DoD.

---

## OAI-03: Content-filter diagnostics parity spike — **DELIVERED FINDINGS-ONLY (PR #141)**

> **Outcome (2026-09-15):** the spike ran against the non-live STE resource and confirmed the
> diagnostic gap, but the stakeholder decision is that **no code change ships**: content
> filtering is guaranteed disabled on all model resources as a standing operational given, so
> FR-11 tripwire logging is not required. The verdict + decision live in
> `04-content-filter-parity-findings.md` §4 and close the D3 gate on OAI-05. A built-and-tested
> FR-11 implementation was deliberately dropped; it is recoverable from PR #141's pre-force-push
> history if the posture ever changes. The story text below is retained as written for the record.

### User story
As a **developer/maintainer preparing the ground for Stage 4**,
I want **a recorded, evidence-based comparison of how an Azure content-filter trip and a truncated/incomplete response surface through openai-java on the `/openai/v1` passthrough surface versus the existing Azure SDK path, with equivalent diagnostic logging added to `OpenAiChatService` if a gap is confirmed**,
so that **operational triage of a filtered or truncated LLM response is no worse on the OpenAI path than it is today, and the Stage 4 chat-deletion decision (OAI-05) has a documented, gate-satisfying verdict to point to**.

### Background
FR-10, FR-11; AC-20…AC-22. Runs **in parallel** with OAI-01/OAI-02 — no code dependency on either. It is an investigation task first, an optional implementation task second: the spike must (1) provoke a filtered *prompt* (input-side, expected `com.openai.errors.BadRequestException`/`OpenAIServiceException`, `code()` = `content_filter`, and critically whether `innererror.content_filter_result` category/severity detail survives into `body()`), (2) provoke a filtered/truncated *completion* (output-side, via `response.incompleteDetails()`), (3) confirm whether any filter annotation appears on a successful, unfiltered response, and (4) confirm a 400 content-filter trip is not silently retried and note how it lands in the async worker (rides queue redelivery up to `maxDequeueCount` 3). This must be provoked against a **non-production** Azure OpenAI resource via the offline harness with a benign-but-reliably-filtered prompt — never production, never real case material.

The findings note is a separate, standalone artefact — **not** this stories file — at `docs/pipeline/DD-43417-openai-sdk-migration/04-content-filter-parity-findings.md` (the design's OQ-4/DD-10 recommendation named this location as `03-...` before this stories file claimed that slot; `04-...` is the correct name in the artefact sequence). If FR-11's diagnostic gap is confirmed, `OpenAiChatService.callModel` gains a WARN on `OpenAIServiceException` carrying `statusCode()`, `code()`, `type()`, `param()` and, if present, the `innererror.content_filter_result` categories/severities — **never** prompt, query or document content — plus an upgraded `incompleteDetails()` branch distinguishing `content_filter` from `max_output_tokens`.

### Acceptance criteria
Delivers AC-20 through AC-22 in `01-requirements.md` verbatim (findings note records the observed failure shape and whether filter annotations are exposed at all on success; the note states a clear parity-or-gap verdict recorded as a Stage 4 gate; any FR-11 logging added contains no prompt/document/query content). Story-specific sharpening:
- [ ] The verdict recorded in the findings note (AC-21) is explicitly linked from the OAI-05 sub-task description once that sub-task exists, closing the loop the constraint (D3) requires.
- [ ] If FR-11 logging is added, its unit test asserts the log line contains status/code/reason and specifically asserts the absence of prompt/query/document strings (not just "does not throw").

### NFR links
- NFR-6 (Observability): any FR-11 logging change carries enough detail to triage (status, retry exhaustion, incomplete/truncation reason) without PII or prompt content.
- NFR-9 (Maintainability): the capability gap (or its closure) is documented rather than silently left open.

### Out of scope for this story
- Deleting `AzureChatService` or any Stage 4 code removal — that is gated **by** this story's verdict but delivered in OAI-05, not here.
- Any change to embeddings (OAI-02) or to `OpenAiClientFactory`'s retry/timeout configuration (OAI-01).
- Provoking a content-filter trip against production or real case material — explicitly disallowed.
- Deciding *whether* the FR-11 logging change is needed before running the spike — that decision is the spike's output, not a precondition.

### Definition of done
- [ ] Findings note committed at `docs/pipeline/DD-43417-openai-sdk-migration/04-content-filter-parity-findings.md`, covering all four investigation points above and stating an explicit verdict (parity achieved, or the specific gap + required FR-11 change).
- [ ] If a gap is confirmed: `OpenAiChatService` logging updated per FR-11, with a new/extended unit test proving both the diagnostic detail and the absence of prompt/document/query content (AC-22).
- [ ] Code reviewed and approved (findings note and, if applicable, the code change).
- [ ] `mvn test -pl ai-document-shared-artefacts` passes if `OpenAiChatService` is touched; `mvn verify` green (Surefire + JaCoCo) on any changed classes.
- [ ] SonarQube quality gate passes on the Azure DevOps CI PR build (only relevant if code changes; the findings-only path may be a docs-only PR with no Sonar-gated code).
- [ ] No integration-test change required — the spike is an offline-harness investigation, not a code path exercised by `ai-service-orchestration-test`.
- [ ] PR description references `DD-43417`, links the findings note, and states the recorded verdict verbatim.

### Notes / open questions
- OQ-4 (spike owner) is still open per the design's "Open questions — recommendations" table — location is settled (`04-content-filter-parity-findings.md`), owner is not; must be assigned before this sub-task is picked up.
- This story's verdict is a **hard gate** on OAI-05 (D3) — OAI-05 must not proceed until the verdict here is recorded, independent of whether OAI-04 (cut-over) has already happened.

---

## OAI-04: Cut-over — flip provider defaults to `openai`, harness move, proven rollback

### User story
As a **release/platform operator**,
I want **both `ChatServiceFactory` and `EmbeddingServiceFactory` defaults flipped to `openai`, the eval harness moved onto `OpenAiEmbeddingService` with `PinnedApiVersionEmbeddingService` deleted, and both the forward (`openai`) and rollback (`azure`) legs proven green on the full integration suite**,
so that **an environment with no provider app settings set runs the GA SDK for chat and embeddings, while I retain a proven, config-only rollback to the Azure SDK path if a regression appears during the bake**.

### Background
FR-12, FR-13, FR-14; AC-23…AC-27. Requires OAI-01 and OAI-02 merged — this story changes the *defaults* those stories introduced, and the harness move depends on `OpenAiEmbeddingService` existing (OAI-02). `TestHarness.java` and `RetrievalSnapshotTool.java` are repointed at `EmbeddingServiceFactory`; `PinnedApiVersionEmbeddingService` and its "hardened resources reject the default preview api-version" comments/README notes are deleted, because the workaround's premise (a preview data-plane api-version) does not exist on the `/openai/v1` surface. Per design recommendation **OQ-5/DD-11**, the release operator's rollout sequencing (embeddings leg first, chat second, with a bake between legs and between environments dev → test → prod) is a deployment-pipeline concern outside this artefact, but the **code** must support either leg being flipped independently at zero cost (two independent app settings) — this story's PR does not itself perform the environment-by-environment rollout. Per **OQ-9/DD-12**, the forward (`openai`) leg runs in CI on this PR; the rollback (`azure`) leg is run locally pre-merge and its output pasted into the PR description, since after this stage the `azure` code path is frozen and awaiting deletion.

### Acceptance criteria
Delivers AC-23 through AC-27 in `01-requirements.md` verbatim (both factories select the OpenAI implementations when unset, with the selection visible in logs; harness uses `OpenAiEmbeddingService` with `PinnedApiVersionEmbeddingService` absent from the tree and no api-version pin remaining anywhere; the `azure` rollback leg passes the full ingestion + answer-generation integration journeys; the `openai` forward leg passes the same journeys with equivalent outcomes; an operator flipping only the app settings back to `azure` and restarting serves traffic on the Azure path with no redeploy or data migration). Story-specific sharpening:
- [ ] Both legs (AC-25, AC-26) are run against the **same** integration-test code in the **same** stage PR, not staggered across separate PRs, so the comparison is apples-to-apples.
- [ ] The `api-contract-check` skill is run once on this PR as a regression assertion (per the design's Contract Impact section) — expected result: no diff against `api-cp-ai-rag`.

### NFR links
- NFR-2 (Reversibility): AC-27 is the explicit proof that rollback is config-only through this stage.
- NFR-8 (Performance/cost): bake-period observation (latency, token usage, groundedness) shows no regression attributable to the SDK change — recorded qualitatively in the PR/rollout notes, not a hard CI gate.

### Out of scope for this story
- The Stage 4 deletion of `AzureChatService`/`AzureEmbeddingService`/`AzureOpenAiClientFactory`/`azure-ai-openai` (OAI-05) — the Azure path stays in the tree as the rollback lever.
- Executing the actual per-environment, embeddings-first staged rollout with bake periods (OQ-5) — that is the manual Azure DevOps deployment pipeline and release-operator activity, out of scope for this code-delivery artefact.
- Defining or closing the OQ-6 "fully exercised in production" gate — tracked separately, feeds OAI-05 only.
- Any change to the content-filter diagnostics behaviour — that is OAI-03's concern and does not block this cut-over (D3 gates only OAI-05's chat *deletion*, not the default flip here).

### Definition of done
- [ ] Code reviewed and approved.
- [ ] `ChatServiceFactory` and `EmbeddingServiceFactory` default arguments flipped from `azure` to `openai` (including the null/blank-input logging branch); `RagHarness.setupEnvVarMap()` default forwarding flipped to match.
- [ ] `TestHarness.java` and `RetrievalSnapshotTool.java` construct embeddings via `EmbeddingServiceFactory`; `PinnedApiVersionEmbeddingService.java` deleted; associated api-version comments and harness README/`.env.sample` notes removed.
- [ ] Factory-default unit tests updated to assert `openai` on unset input; harness compiles and its own tests pass without `PinnedApiVersionEmbeddingService`.
- [ ] `mvn test` passes for all touched modules; `mvn verify` green (Surefire + JaCoCo) with coverage maintained on changed classes.
- [ ] SonarQube quality gate passes on the Azure DevOps CI PR build.
- [ ] Full `./ai-service-orchestration-test/run-integration-test.sh` run **twice** — once with both provider vars `openai` (CI, forward leg, AC-26), once with both `azure` (local pre-merge, rollback leg, AC-25) — both green, with the rollback-leg output pasted into the PR description.
- [ ] `api-contract-check` skill run once and confirms no diff against `api-cp-ai-rag`.
- [ ] Root `CLAUDE.md` and all touched `Azure/local.settings.sample.json` / `.env.sample` files updated to reflect the new `openai` default for both toggles.
- [ ] PR description references `DD-43417`, states the rollback-leg evidence, and notes that the per-environment staged rollout (OQ-5) is a separate, subsequent operational activity.

### Notes / open questions
- OQ-5 (cut-over granularity) and OQ-9 (integration matrix cost) are resolved by design recommendation (DD-11: embeddings-first, per environment, with a bake; DD-12: forward leg in CI, rollback leg local) but still need the release owner's explicit sign-off before the *rollout* (not this PR) proceeds, per the design's Rollout & rollback section.
- This story's merge does **not** by itself change production behaviour anywhere — deployment is a separate, manual Azure DevOps pipeline step, and any environment wanting to opt out of the new default can pre-pin its provider app settings to `azure` ahead of deploy.

---

## OAI-05: [DEFERRED — NOT SCHEDULED] Removal of the Azure OpenAI SDK

### User story
As a **developer/maintainer completing the migration**,
I want **`AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` and their tests deleted, `com.azure:azure-ai-openai` removed from both `pom.xml` dependency-management blocks, and both factories made to fail fast on a stale `provider=azure` setting**,
so that **the service depends on a single GA SDK for all model access, with no dead rollback code left behind and no ambiguous "unknown value" error masking a stale, unsupported configuration**.

### Background
FR-15, FR-16, FR-17; AC-28…AC-30. **This story is explicitly DEFERRED and not scheduled by this artefact.** It has two independent, mandatory preconditions per constraints D3 and D4:
1. **OQ-6 gate (D4):** the OpenAI path (from OAI-04) must be confirmed "fully exercised in production" — a concrete definition (elapsed time, transaction volume, absence of SDK-attributable errors, groundedness distribution within normal variance) is still open and must be agreed by the technical approver/Jira reporter before this story can be picked up.
2. **OAI-03 verdict (D3):** the content-filter diagnostics parity spike must have recorded its verdict at `docs/pipeline/DD-43417-openai-sdk-migration/04-content-filter-parity-findings.md`, and if a gap was found, the FR-11 logging change must be in place — the chat-side deletion here must not proceed ahead of that verdict.

Once both gates close, the deletion is mechanical: `AzureChatService`, `AzureEmbeddingService` (and its `AzureEmbeddingServiceTest`/`DefaultJsonReader` fixtures, OQ-7), `AzureOpenAiClientFactory` and their three test classes are removed; `com.azure:azure-ai-openai` is dropped from the root `pom.xml` `<dependencyManagement>` and from `ai-document-shared-artefacts/pom.xml`. `com.azure:azure-identity` and `ClientConfiguration` are explicitly **retained** (FR-17/D5) — they still serve the bearer-token supplier and the six non-OpenAI Azure clients (AI Search, two Blob clients, Table, Document Intelligence, migration-tool index admin), which this story must not touch. This is the migration's **one-way door**: after this lands, rollback to the Azure SDK means a code revert and redeploy, not a config flip.

### Acceptance criteria
Delivers AC-28 through AC-30 in `01-requirements.md` verbatim (the three Azure classes and their tests are absent from the tree and `mvn dependency:tree` shows no `com.azure:azure-ai-openai` in any module; a stale `provider=azure` app setting on either factory fails fast with an explicit "provider 'azure' has been removed" message rather than the generic unknown-value error; `com.azure:azure-identity` and `ClientConfiguration` remain present and the six non-OpenAI Azure clients build and behave unchanged). Story-specific sharpening:
- [ ] The removed-provider error message (AC-29) is distinct in wording and exception type from the unknown-value `IllegalArgumentException` used for genuinely unrecognised values, so an operator reading the log can tell "you typed it wrong" from "this option no longer exists" apart.
- [ ] `ClientConfigurationTest` (the six-client regression suite) is explicitly re-run and confirmed green as part of this PR, not assumed unaffected.

### NFR links
- NFR-2 (Reversibility): this story is the deliberate, documented exception to reversibility — the one-way door is called out explicitly rather than implied.
- NFR-9 (Maintainability): completes the goal of depending on a single GA SDK for model access; the deletion is scoped exactly to what's superseded, nothing from the retained Azure surface is touched.

### Out of scope for this story
- Defining or evidencing the OQ-6 "fully exercised in production" criteria — that is a separate, stakeholder-owned decision tracked outside this artefact, consumed here only as a precondition.
- Any change to `azure-identity`, `ClientConfiguration`, or the six non-OpenAI Azure clients (AI Search, two Blob clients, Table, Document Intelligence, migration-tool index admin) — explicitly retained per FR-17/D5.
- The optional `OPENAI_CLIENT_*` env-var rename with legacy-name fallback (OQ-1) — noted as an optional, not required, Stage 4 follow-up.
- Scheduling or executing the production soak itself — an operational activity, not a development task.

### Definition of done
- [ ] **Gate check recorded in the PR description before work starts:** OQ-6 evidence (criteria + measured values) and a link to the OAI-03 findings-note verdict, both confirming the preconditions are satisfied.
- [ ] Code reviewed and approved.
- [ ] `AzureChatService`, `AzureEmbeddingService`, `AzureOpenAiClientFactory` and their test classes (`AzureChatServiceTest`, `AzureEmbeddingServiceTest`, `AzureOpenAiClientFactoryTest`) deleted.
- [ ] Harness `ContentFilterSpikeTool` updated or retired — its probe 5 constructs `AzureChatService` directly (the last such usage, noted at the DD-43423 review) and will not compile once the class is deleted.
- [ ] `com.azure:azure-ai-openai` removed from the root `pom.xml` and `ai-document-shared-artefacts/pom.xml`; `mvn dependency:tree` confirms it is absent from every module.
- [ ] Both `ChatServiceFactory` and `EmbeddingServiceFactory` gain an explicit `azure` branch throwing the "provider 'azure' has been removed" error before the default branch, with a unit test asserting the message and exception type.
- [ ] `mvn test` passes across all modules; `mvn verify` green (Surefire + JaCoCo); `ClientConfigurationTest` explicitly confirmed green.
- [ ] SonarQube quality gate passes on the Azure DevOps CI PR build.
- [ ] Full `ai-service-orchestration-test` suite passes on the single remaining (`openai`) path.
- [ ] Root `CLAUDE.md` updated to remove references to the now-deleted `azure` provider option and to record the completed migration.
- [ ] PR description references `DD-43417` and links both closed gates (OQ-6 evidence, OAI-03 verdict).

### Notes / open questions
- **Do not schedule this story until both gates in Background are explicitly closed.** It is included in this artefact for scoping/sizing purposes only.
- OQ-6 owner and concrete criteria are still to be confirmed (per `02-design.md`'s "Open questions — recommendations" table) — this is the single biggest unresolved blocker to scheduling this story.
- If OAI-03 finds a content-filter diagnostics gap and closes it via FR-11 logging, that logging (already in `OpenAiChatService` from OAI-03) needs no further change here — this story only deletes the Azure-side equivalent, it does not touch the OpenAI-side diagnostics.
