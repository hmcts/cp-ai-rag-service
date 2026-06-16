# Azure Functions (Java) — context overlay

This repo is a **multi-module Maven project of Java Azure Functions**, not a Spring
Boot service on AKS. It lives in the HMCTS/CPP Azure family, so the plugin's
security, Azure-SDK, and SDLC-shape guidance still applies — but the runtime,
build, logging, health, and deploy mechanics differ. This file is the source of
truth for those deltas. Where it conflicts with the `hmcts-sdlc-orchestrator`
plugin's `context/tech-stack.md`, `context/azure-cloud-native.md`, or
`context/logging-standards.md`, **this file wins**.

## What this repo is
- Five deployable Azure Functions + one shared library + one integration-test module.
  See the module table in the root `CLAUDE.md`.
- Triggers/bindings, not controllers: HTTP (`@FunctionName` + `HttpTrigger`),
  Queue, and Blob triggers. Entry points are `@FunctionName`-annotated methods.
- Group/package: `uk.gov.hmcts.cp` (OpenAPI-generated models under
  `uk.gov.hmcts.cp.openapi`).

## Deltas from the plugin's Spring Boot assumptions

| Concern | Plugin assumes (Spring Boot / AKS) | This repo (Azure Functions Java)                                                                                                                                     |
|---|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Build | Gradle `./gradlew build` | **Maven** `mvn clean package`; run locally `cd <fn> && mvn azure-functions:run`                                                                                      |
| Packaging | Spring Boot fat jar in a container | Functions artefact via `azure-functions-maven-plugin` (`package` goal)                                                                                               |
| Entry point | `@RestController` / `@Service` | `@FunctionName` methods with trigger/binding annotations                                                                                                             |
| Health | Actuator `/actuator/health/{liveness,readiness}` | **None** — the Functions host owns health. Do not add actuator probes.                                                                                               |
| Logging | logstash-logback-encoder JSON to stdout + MDC | `context.getLogger()` (`java.util.logging`) via the function's `ExecutionContext`; Application Insights auto-instruments. **No `logback.xml`, no logstash encoder.** |
| Config | env vars only (12-factor) | App settings / `local.settings.json` (git-ignored; copy from `local.settings.sample.json`). Same "no hardcoded config" principle.                                    |
| Container | Dockerfile `USER app`, HMCTS ACR base image | No Dockerfile — runs on the Functions host (Consumption/Premium plan)                                                                                                |
| Deploy | Helm chart + Flux CD on AKS, gated as SDLC Stage 8 | **Separate Azure DevOps deployment pipeline, run manually after the PR merges** — detached from PR review and outside the orchestrator (see "Deployment" below).     |
| CI | GitHub Actions / Jenkins | **Azure DevOps** `azure-pipelines.yaml` (`cpp-azure-devops-templates`)                                                                                               |
| New-service template | `service-hmcts-crime-springboot-template` | **No template applies.** Do not run `springboot-*-from-template`, `context-scaffold`, or `context-service-guide`. Follow the existing module layout.                 |

## Secrets / Managed Identity
The plugin's hard rule is "no connection strings, SAS tokens, or account keys —
Managed Identity only", and this repo follows it. All Azure SDK clients
authenticate via `DefaultAzureCredential` (`CredentialUtil.getCredentialInstance`),
and the Functions storage triggers/outputs use identity-based bindings (the
`connection` attribute names an app-setting prefix whose `__accountName` value the
host resolves under managed identity — there is no account key in play).
- Use `DefaultAzureCredential` (Managed Identity) for all Azure access; do not
  add `.connectionString(...)` / account-key auth to the SDK client factories.
- Never commit real connection strings, SAS tokens, or keys — `local.settings.json`
  is git-ignored; only `*.sample.json` is committed.
- `AzureWebJobsStorage` (Functions host runtime store) and the Application Insights
  connection string are host/telemetry config, not storage-account credentials.

### Known exception: `RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING` (Azure Monitor)
`AzureMonitorService` (answer-scoring function) configures the Azure Monitor
OpenTelemetry exporter with `RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING` and
**deliberately does not** add a managed-identity `TokenCredential`. This is **not**
an oversight or tech-debt — it is the correct configuration for this SDK:
- The Azure Monitor exporter (`AzureMonitorAutoConfigureOptions`) has **no
  `.endpoint(...)` setter** — unlike the storage builders. The connection string is
  the only way to supply the ingestion endpoint + App Insights resource id, so it
  cannot be dropped the way storage connection strings were.
- A `.credential(...)` only switches ingestion auth to Microsoft Entra ID, and it
  takes effect **only if local auth is disabled** on the App Insights resource. It
  also requires the function's managed identity to hold the **Monitoring Metrics
  Publisher** role — without that role, supplying a credential makes telemetry
  publishing fail (401/403) where instrumentation-key auth would have worked.
- Both of those are **resource/infra changes**, not code. Until they are made,
  adding the credential is either extraneous (local auth on) or breaking (no RBAC
  role). So leave `AzureMonitorService` on connection-string/iKey auth; do not
  re-flag it as a managed-identity cleanup target.

## Deployment — manual, pipeline-driven, outside the orchestrator
Deployment of the functions is **not part of the orchestrator's SDLC pipeline**.
The plugin's Stage 8 ("Deploy Sandbox" / the `deployer` agent) and its
GitHub-Actions/Helm/Flux assumptions **do not apply here** — do not invoke the
`deployer` agent or treat deploy as an orchestrator step.

Instead:
- Deployment is performed by a **separate Azure DevOps deployment pipeline**, not
  from a local machine and not via the orchestrator. (The underlying mechanism is
  the `azure-functions-maven-plugin` deploy goal, but it is run by that pipeline.)
- It is **detached from the PR review process**. PR review + CI (build + SonarQube)
  is where the orchestrator's involvement ends; a green PR does not trigger a deploy.
- It is **run manually, after the PR has been merged**, by whoever owns the release —
  a deliberate human action against the chosen environment, decoupled from the merge.

So the orchestrator pipeline here covers up to and including CI on the PR (Stages
5–7); merge and deployment are downstream, manual, and out of its scope.

## Build & test quick reference
```bash
mvn clean compile                                  # build all modules
mvn test                                           # all unit tests
mvn test -pl <function-module> -Dtest=SomeTest     # single test class
mvn verify                                          # tests + coverage
mvn verify -P ai-rag-integration-test              # integration tests
mvn clean package -DskipTests                       # package for deployment
cd <function-module> && mvn azure-functions:run     # run one function locally
```
