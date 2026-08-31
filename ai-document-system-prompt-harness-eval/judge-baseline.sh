#!/usr/bin/env bash
#
# Offline judge pass over two persisted harness runs (harness-results/*.json) — quality
# verdicts with NO answer regeneration. See BaselineJudgeTool for the full contract.
#
#   Usage (from anywhere):
#     ./judge-baseline.sh <file-A> <file-B> <judge> [model-A] [model-B]
#
#   file-A / file-B   run files (bare names resolve inside harness-results/); A = baseline side
#   judge             [provider:]deployment[@endpoint], e.g.
#                       local:gpt-oss-120b@http://localhost:1234/v1   (fully offline)
#                       gpt-5.1                                       (Azure, when available)
#   model-A / model-B llmLabel to select when a file holds more than one model's rows
#
# Optional via calling environment:
#   HARNESS_JUDGE_COSINE          off (default) | local:<model>@<endpoint> for the prose-cosine
#                                 flag, e.g. local:text-embedding-nomic-embed-text-v1.5@http://localhost:1234/v1
#   HARNESS_JUDGE_DELAY_SECONDS   pacing before each judge call (default 0; set for cloud judges)
#
# .env is sourced for the Azure endpoints an unprefixed cloud judge needs; local judges run
# without any Azure access.
#
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <file-A> <file-B> <judge> [model-A] [model-B]" >&2
  exit 2
fi

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
MODULE="ai-document-system-prompt-harness-eval"
ENV_FILE="${MODULE_DIR}/.env"

# Remember judge-tool settings supplied by the caller before .env sourcing could clobber
# anything (the HARNESS_JUDGE_* keys are not in .env, but keep the pattern defensive).
PRE_COSINE="${HARNESS_JUDGE_COSINE:-}"
PRE_DELAY="${HARNESS_JUDGE_DELAY_SECONDS:-}"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

export HARNESS_JUDGE_FILE_A="$1"
export HARNESS_JUDGE_FILE_B="$2"
export HARNESS_JUDGE_LLM="$3"
[[ $# -ge 4 ]] && export HARNESS_JUDGE_MODEL_A="$4"
[[ $# -ge 5 ]] && export HARNESS_JUDGE_MODEL_B="$5"
[[ -n "${PRE_COSINE}" ]] && export HARNESS_JUDGE_COSINE="${PRE_COSINE}"
[[ -n "${PRE_DELAY}" ]] && export HARNESS_JUDGE_DELAY_SECONDS="${PRE_DELAY}"

echo "[judge-baseline] A: ${HARNESS_JUDGE_FILE_A} (${4:-<single model>})"
echo "[judge-baseline] B: ${HARNESS_JUDGE_FILE_B} (${5:-<single model>})"
echo "[judge-baseline] judge: ${HARNESS_JUDGE_LLM}  cosine: ${HARNESS_JUDGE_COSINE:-off}"

cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java \
  -Dharness.mainClass=uk.gov.moj.cp.harness.BaselineJudgeTool
