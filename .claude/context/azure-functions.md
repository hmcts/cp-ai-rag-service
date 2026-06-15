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

| Concern | Plugin assumes (Spring Boot / AKS) | This repo (Azure Functions Java) |
|---|---|---|
| Build | Gradle `./gradlew build` | **Maven** `mvn clean package`; run locally `cd <fn> && mvn azure-functions:run` |
| Packaging | Spring Boot fat jar in a container | Functions artefact via `azure-functions-maven-plugin` (`package` goal) |
| Entry point | `@RestController` / `@Service` | `@FunctionName` methods with trigger/binding annotations |
| Health | Actuator `/actuator/health/{liveness,readiness}` | **None** — the Functions host owns health. Do not add actuator probes. |
| Logging | logstash-logback-encoder JSON to stdout + MDC | `context.getLogger()` (`java.util.logging`) via the function's `ExecutionContext`; Application Insights auto-instruments. **No `logback.xml`, no logstash encoder.** |
| Config | env vars only (12-factor) | App settings / `local.settings.json` (git-ignored; copy from `local.settings.sample.json`). Same "no hardcoded config" principle. |
| Container | Dockerfile `USER app`, HMCTS ACR base image | No Dockerfile — runs on the Functions host (Consumption/Premium plan) |
| Deploy | Helm chart + Flux CD on AKS | `mvn azure-functions:deploy` (`azure-functions-maven-plugin`) to `fa-{env}-ai-document-{name}` in `RG-{env}-CPAIRAGSERVICE` |
| CI | GitHub Actions / Jenkins | **Azure DevOps** `azure-pipelines.yaml` (`cpp-azure-devops-templates`) |
| New-service template | `service-hmcts-crime-springboot-template` | **No template applies.** Do not run `springboot-*-from-template`, `context-scaffold`, or `context-service-guide`. Follow the existing module layout. |

## Secrets / Managed Identity — known deviation
The plugin's hard rule is "no connection strings, SAS tokens, or account keys —
Managed Identity only". This repo **currently uses connection strings** (e.g.
`AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING`) supplied via app settings and
`local.settings.json`. Treat this as a **tracked deviation**, not a blocker:
- Prefer `DefaultAzureCredential` (Managed Identity) for new Azure access.
- Never commit real connection strings, SAS tokens, or keys — `local.settings.json`
  is git-ignored; only `*.sample.json` is committed.
- If you must keep a connection string, that's allowed for now — surface it as
  tech-debt / an ADR rather than failing the review.

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
