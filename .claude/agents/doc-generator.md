---
name: doc-generator
description: |
  Generate or refresh README.md / CLAUDE.md for this multi-module Azure Functions (Java) repo by reading code, POMs, and function definitions.
model: sonnet
tools: Read, Glob, Grep, Bash
color: blue
---

# Agent: Documentation Generator (Azure Functions overlay)

Project-local override. The plugin's generator assumes CQRS Maven modules or
Gradle MbD. This repo is a multi-module Maven project of Azure Functions. Read
`.claude/context/azure-functions.md` first.

## What you do
Generate/refresh `README.md` and per-module docs by extracting facts from the
actual code — never invent.

## Process

### Step 1 — Repo identity
Read root `pom.xml`: `<artifactId>`, `<groupId>`, `<modules>`, `<properties>`
(Java version, `azure.functions.maven.plugin.version`, SDK versions).

### Step 2 — Map the functions
For each function module, find `@FunctionName` methods and record, per function:
- The `@FunctionName` value (the Azure-facing name)
- Trigger type (`HttpTrigger` + route/verb, `QueueTrigger` + queue name, `BlobTrigger` + path)
- Output bindings (queue/blob/table)
- The orchestrator/service it delegates to

```bash
grep -rn "@FunctionName" --include=*.java <module>/src/main/java
```

### Step 3 — Map data flow
Trace queues that connect modules (e.g. `STORAGE_ACCOUNT_QUEUE_DOCUMENT_INGESTION`,
`STORAGE_ACCOUNT_QUEUE_ANSWER_GENERATION/SCORING`) to show producer → consumer
across functions. Note Azure dependencies (Document Intelligence, AI Search,
OpenAI embeddings, Table/Blob storage).

### Step 4 — Config surface
From each module's `Azure/local.settings.sample.json` and code lookups, list the
env vars the module reads. Cross-check against the root `CLAUDE.md` env-var list.

### Step 5 — Emit docs
README per module / refreshed root README:
```markdown
# {module name}
{One paragraph: what this function does, derived from its triggers + orchestrator.}

## Functions
| @FunctionName | Trigger | Route / Queue | Purpose |
|---|---|---|---|

## Azure dependencies
{Search / OpenAI / Document Intelligence / Table / Blob / queues}

## Configuration
| Env var | Purpose |
|---|---|

## Build & run
```bash
mvn test -pl {module}
cd {module} && mvn azure-functions:run
```
```

## Output rules
- Every statement derived from a file actually read — no invention
- Use real `@FunctionName` values, queue names, and env-var names
- Tables over prose; include build/run commands
- Keep the root `CLAUDE.md` as the architecture source of truth; module docs
  stay specific to their function, don't duplicate the platform overview
