#!/usr/bin/env bash
#
# Captures the retrieval half of the harness pipeline (query embedding → AI Search →
# containment-dedup → MMR) into a local JSON snapshot under retrieval-snapshots/, so later
# evaluation runs can replay the exact chunks + embeddings without Azure embedding/search
# access. Makes NO LLM calls. See RetrievalSnapshotTool for the snapshot layout.
#
#   Usage (from anywhere):
#     ./capture-retrieval-snapshot.sh [query-file.json]
#
# The optional argument selects the query set under src/main/resources/user-queries/ and
# OVERRIDES the HARNESS_QUERY_FILE value from .env (default: user-queries-version-test.json,
# which carries both the prod and test query versions).
#
# Configuration comes from this module's .env (same file run-harness.sh uses). Required:
#   HARNESS_DOCUMENT_IDS, AZURE_EMBEDDING_SERVICE_ENDPOINT,
#   AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME, AZURE_SEARCH_SERVICE_ENDPOINT,
#   AZURE_SEARCH_SERVICE_INDEX_NAME (+ az login)
# Optional:
#   HARNESS_MAX_QUERIES     cap the base queries (same semantics as run-harness.sh)
#   HARNESS_SNAPSHOT_DIR    output directory (default: <module>/retrieval-snapshots)
#
# NOTE: snapshots contain verbatim case-document chunk content — the output directory is
# git-ignored and snapshot files must never be committed.
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

# The argument wins over .env: the capture usually targets the version-test set (prod + test
# prompts) even when .env is currently configured for a model-test harness run.
export HARNESS_QUERY_FILE="${1:-user-queries-version-test.json}"

echo "[capture-retrieval-snapshot] query file : ${HARNESS_QUERY_FILE}"
echo "[capture-retrieval-snapshot] documents  : ${HARNESS_DOCUMENT_IDS:-<unset>}"
echo "[capture-retrieval-snapshot] search idx : ${AZURE_SEARCH_SERVICE_INDEX_NAME:-<unset>}"

cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java \
  -Dharness.mainClass=uk.gov.moj.cp.harness.RetrievalSnapshotTool
