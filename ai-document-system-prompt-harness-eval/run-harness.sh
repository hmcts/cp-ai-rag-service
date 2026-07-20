#!/usr/bin/env bash
#
# Runs the cross-model system-prompt evaluation harness (TestHarness).
#
# Configuration comes SOLELY from the local .env file in this module directory (copy
# .env.sample to .env and populate it) — this script applies no defaults and no overrides;
# what the .env says is what the harness runs with. The file is sourced into the environment
# because the production services the harness drives read their config via System.getenv.
# Authentication is DefaultAzureCredential, so `az login` first.
#
#   Usage (from anywhere):
#     ./run-harness.sh
#
# See .env.sample for the full list of knobs. Two that matter for gpt-5.1 runs: set
# HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS=300 and HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS=600
# (or higher) in .env — reasoning calls can run for minutes with no bytes flowing, and this
# script no longer floors them for you.
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

# Load .env into the environment (auto-export everything assigned while sourcing).
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

echo "[run-harness] module: ${MODULE_DIR}"
echo "[run-harness] prompts: ${HARNESS_SYSTEM_PROMPTS:-<unset>}"
echo "[run-harness] models: ${HARNESS_LLM_DEPLOYMENTS:-<unset>}  reps: ${HARNESS_REPETITIONS:-<unset>}  delay: ${HARNESS_CALL_DELAY_SECONDS:-<unset>}s"
echo "[run-harness] reasoning_effort: ${LLM_REASONING_EFFORT:-<unset>}  max_completion_tokens: ${LLM_MODEL_RESPONSE_MAX_TOKENS:-<unset>}  read_timeout: ${HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS:-<unset>}s"
echo "[run-harness] guard: ${CITATION_GUARD_MODE:-<unset>}  judge: ${HARNESS_JUDGE:-<unset>}/${HARNESS_JUDGE_DEPLOYMENT:-<unset>}  knn/top/mmr-final: ${SEARCH_NEAREST_NEIGHBOURS_COUNT:-<unset>}/${SEARCH_TOP_RESULTS_COUNT:-<unset>}/${SEARCH_MMR_FINAL_COUNT:-<unset>}"
echo "[run-harness] ensure you have run 'az login' (DefaultAzureCredential)."

# Build the module + its upstream deps (shared-artefacts, answer-retrieval) and install to
# the local repo so the second invocation's exec:java resolves them, then run the harness.
cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java
