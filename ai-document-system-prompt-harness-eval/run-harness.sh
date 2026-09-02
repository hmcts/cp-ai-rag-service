#!/usr/bin/env bash
#
# Runs the cross-model system-prompt evaluation harness (TestHarness).
#
# Configuration comes from the local .env file in this module directory (copy .env.sample
# to .env and populate it). The file is sourced into the environment because the production
# services the harness drives read their config via System.getenv.
# Authentication is DefaultAzureCredential, so `az login` first.
#
# Ad-hoc overrides: HARNESS_LLM_DEPLOYMENTS, HARNESS_QUERY_FILE and HARNESS_SYSTEM_PROMPTS
# may be supplied via the calling environment and WIN over the .env values (e.g. a one-off
# baseline run against a different model pair or a candidate prompt variant without editing
# .env). Every other knob comes from .env alone; vars not present in .env (e.g.
# HARNESS_MAX_QUERIES, HARNESS_RETRIEVAL_SNAPSHOT) pass through from the calling
# environment as normal.
#
#   Usage (from anywhere):
#     ./run-harness.sh
#
# See .env.sample for the full list of knobs. Two that matter for gpt-5.1 runs: set
# HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS=300 and HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS=600
# (or higher) in .env — reasoning calls can run for minutes with no bytes flowing, and this
# script no longer floors them for you.
#
#   Anthropic Claude on Azure AI Foundry (harness-only; see .env.sample):
#     HARNESS_LLM_DEPLOYMENTS=gpt-4o-response-generation,anthropic:claude-sonnet-4-6
#     ANTHROPIC_FOUNDRY_RESOURCE=<res>    # or ANTHROPIC_FOUNDRY_BASE_URL (exactly one)
#     ANTHROPIC_FOUNDRY_API_KEY=...       # optional; default is a DefaultAzureCredential token
#   Any entry may carry its own endpoint via an "@https://..." suffix (overrides the global
#   endpoint env var for that model only), e.g.:
#     anthropic:claude-sonnet-4-6@https://<foundry-resource>.services.ai.azure.com/anthropic
#   Note: the AZURE_CLIENT_* / HTTP_CLIENT_* retry+timeout vars apply to the Azure SDK
#   clients only — the Anthropic SDK uses its own defaults (10-minute request timeout,
#   2 retries).
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
MODULE="ai-document-system-prompt-harness-eval"
ENV_FILE="${MODULE_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: ${ENV_FILE} not found. Copy ${MODULE_DIR}/.env.sample to .env and populate it." >&2
  exit 1
fi

# Remember explicitly supplied overrides before .env clobbers them (see header).
PRE_SET_LLM_DEPLOYMENTS="${HARNESS_LLM_DEPLOYMENTS:-}"
PRE_SET_QUERY_FILE="${HARNESS_QUERY_FILE:-}"
PRE_SET_SYSTEM_PROMPTS="${HARNESS_SYSTEM_PROMPTS:-}"

# Load .env into the environment (auto-export everything assigned while sourcing).
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

if [[ -n "${PRE_SET_LLM_DEPLOYMENTS}" ]]; then
  export HARNESS_LLM_DEPLOYMENTS="${PRE_SET_LLM_DEPLOYMENTS}"
  echo "[run-harness] HARNESS_LLM_DEPLOYMENTS overridden from calling environment"
fi
if [[ -n "${PRE_SET_QUERY_FILE}" ]]; then
  export HARNESS_QUERY_FILE="${PRE_SET_QUERY_FILE}"
  echo "[run-harness] HARNESS_QUERY_FILE overridden from calling environment"
fi
if [[ -n "${PRE_SET_SYSTEM_PROMPTS}" ]]; then
  export HARNESS_SYSTEM_PROMPTS="${PRE_SET_SYSTEM_PROMPTS}"
  echo "[run-harness] HARNESS_SYSTEM_PROMPTS overridden from calling environment"
fi

echo "[run-harness] module: ${MODULE_DIR}"
echo "[run-harness] prompts: ${HARNESS_SYSTEM_PROMPTS:-<unset>}"
echo "[run-harness] models: ${HARNESS_LLM_DEPLOYMENTS:-<unset>}  reps: ${HARNESS_REPETITIONS:-<unset>}  delay: ${HARNESS_CALL_DELAY_SECONDS:-<unset>}s  parallel_models: ${HARNESS_PARALLEL_MODELS:-true}"
echo "[run-harness] reasoning_effort: ${LLM_REASONING_EFFORT:-<unset>}  max_completion_tokens: ${LLM_MODEL_RESPONSE_MAX_TOKENS:-<unset>}  read_timeout: ${HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS:-<unset>}s"
echo "[run-harness] guard: ${CITATION_GUARD_MODE:-<unset>}  judge: ${HARNESS_JUDGE:-<unset>}/${HARNESS_JUDGE_DEPLOYMENT:-<unset>}  knn/top/mmr-final: ${SEARCH_NEAREST_NEIGHBOURS_COUNT:-<unset>}/${SEARCH_TOP_RESULTS_COUNT:-<unset>}/${SEARCH_MMR_FINAL_COUNT:-<unset>}"
echo "[run-harness] retrieval: ${HARNESS_RETRIEVAL_SNAPSHOT:-<live Azure embed+search>}  query_file: ${HARNESS_QUERY_FILE:-<default>}"
echo "[run-harness] ensure you have run 'az login' (DefaultAzureCredential)."

# Build the module + its upstream deps (shared-artefacts, answer-retrieval) and install to
# the local repo so the second invocation's exec:java resolves them, then run the harness.
cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java
