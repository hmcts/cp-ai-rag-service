#!/usr/bin/env bash
#
# Runs the cross-model system-prompt evaluation harness (TestHarness).
#
# Configuration is read from a local .env file in this module directory: copy .env.sample
# to .env and populate it. This script sources .env into the environment, because the
# production services the harness drives read their config via System.getenv (which the JVM
# cannot set for itself). Authentication is DefaultAzureCredential, so `az login` first.
#
#   Usage (from anywhere):
#     ./run-harness.sh
#
#   Knobs (set in .env, or export before running to override):
#     HARNESS_REPETITIONS=1               # fast smoke test (default 2)
#     HARNESS_LLM_DEPLOYMENTS=gpt-5.1     # single model (default: gpt-5.1,gpt-4o-response-generation)
#     HARNESS_MAX_QUERIES=1               # only the first N queries (default: all)
#     HARNESS_CALL_DELAY_SECONDS=5        # delay before each LLM call (default 5)
#     LLM_REASONING_EFFORT=none           # reasoning models only (default none)
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

# Harness-knob defaults, applied only if .env / the shell did not set them.
export HARNESS_REPETITIONS="${HARNESS_REPETITIONS:-2}"
export HARNESS_LLM_DEPLOYMENTS="${HARNESS_LLM_DEPLOYMENTS:-gpt-5.1,gpt-4o-response-generation}"
export HARNESS_CALL_DELAY_SECONDS="${HARNESS_CALL_DELAY_SECONDS:-5}"
export LLM_MODEL_RESPONSE_MAX_TOKENS="${LLM_MODEL_RESPONSE_MAX_TOKENS:-7000}"
export LLM_REASONING_EFFORT="${LLM_REASONING_EFFORT:-none}"

# gpt-5.1 reasoning calls can run for minutes with no bytes flowing, blowing a short read
# timeout. Force the HTTP timeouts UP TO A FLOOR for the harness, respecting any higher value.
if [[ "${HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS:-0}" -lt 300 ]]; then
  export HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS=300
fi
if [[ "${HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS:-0}" -lt 600 ]]; then
  export HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS=600
fi

echo "[run-harness] module: ${MODULE_DIR}"
echo "[run-harness] models: ${HARNESS_LLM_DEPLOYMENTS}  reps: ${HARNESS_REPETITIONS}  delay: ${HARNESS_CALL_DELAY_SECONDS}s"
echo "[run-harness] reasoning_effort: ${LLM_REASONING_EFFORT}  max_completion_tokens: ${LLM_MODEL_RESPONSE_MAX_TOKENS}  read_timeout: ${HTTP_CLIENT_READ_TIMEOUT_IN_SECONDS}s"
echo "[run-harness] ensure you have run 'az login' (DefaultAzureCredential)."

# Build the module + its upstream deps (shared-artefacts, answer-retrieval) and install to
# the local repo so the second invocation's exec:java resolves them, then run the harness.
cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java
