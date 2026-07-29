#!/usr/bin/env bash
#
# Uploads case documents through the service's real ingestion pipeline (initiate → SAS PUT →
# ingestion poll) and prints the document ids to add to HARNESS_DOCUMENT_IDS for evaluation.
#
#   Usage (from anywhere; paths must not contain spaces):
#     ./upload-document.sh /path/to/case-file.pdf [more files...]
#
# Configuration comes from this module's .env (same file run-harness.sh uses). Required:
#   HARNESS_UPLOAD_FUNCTION_BASE_URL   metadata-check function app base URL incl. route prefix
#                                      (e.g. https://<app>.azurewebsites.net/api or
#                                       http://localhost:7071/api)
# Recommended:
#   HARNESS_STATUS_FUNCTION_BASE_URL   status-check function app base URL, same convention —
#                                      without it the tool does not wait for INGESTION_SUCCESS
# Optional:
#   HARNESS_UPLOAD_FUNCTION_KEY / HARNESS_STATUS_FUNCTION_KEY   x-functions-key values
#   HARNESS_CLIENT_ID                  client identity header value (enforcement-gated envs)
#   HARNESS_UPLOAD_METADATA            key=value,key2=value2 metadataFilter (default: random caseId)
#   HARNESS_UPLOAD_POLL_TIMEOUT_SECONDS  ingestion wait per document (default 600)
#
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <file> [more files...]" >&2
  exit 2
fi

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
MODULE="ai-document-system-prompt-harness-eval"
ENV_FILE="${MODULE_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: ${ENV_FILE} not found. Copy ${MODULE_DIR}/.env.sample to .env and populate it." >&2
  exit 1
fi

# Resolve file arguments to absolute paths BEFORE cd-ing to the repo root.
FILES=()
for f in "$@"; do
  if [[ "$f" = /* ]]; then FILES+=("$f"); else FILES+=("$(pwd)/$f"); fi
done

# Load .env into the environment (auto-export everything assigned while sourcing).
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

echo "[upload-document] upload host: ${HARNESS_UPLOAD_FUNCTION_BASE_URL:-<unset>}"
echo "[upload-document] status host: ${HARNESS_STATUS_FUNCTION_BASE_URL:-<unset — will not wait for ingestion>}"
echo "[upload-document] files: ${FILES[*]}"

cd "${REPO_ROOT}"
mvn -q -pl "${MODULE}" -am -DskipTests install
mvn -q -pl "${MODULE}" exec:java \
  -Dharness.mainClass=uk.gov.moj.cp.harness.DocumentUploadTool \
  -Dexec.args="${FILES[*]}"
