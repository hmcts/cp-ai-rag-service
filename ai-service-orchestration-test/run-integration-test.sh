#!/usr/bin/env bash
#
# Runs the RAG service integration tests (OrchestrationIT, via maven-failsafe) against real Azure.
#
# Configuration is read from a local .env file in this module directory: copy .env.sample
# to .env and populate it. This script sources .env into the environment, because
# FunctionTestBase reads its config via System.getenv and forwards it to each locally
# started Function host. Authentication is DefaultAzureCredential, so `az login` first.
# Azure Functions Core Tools (`func`) must be on the PATH.
#
#   Usage (from anywhere):
#     ./run-integration-test.sh                 # package the function apps, then run the tests
#     SKIP_BUILD=true ./run-integration-test.sh # reuse already-packaged function apps
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
MODULE="ai-service-orchestration-test"
ENV_FILE="${MODULE_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "ERROR: ${ENV_FILE} not found. Copy ${MODULE_DIR}/.env.sample to .env and populate it." >&2
  exit 1
fi

if ! command -v func >/dev/null 2>&1; then
  echo "ERROR: Azure Functions Core Tools ('func') not found on PATH. Install: brew install azure-functions-core-tools@4" >&2
  exit 1
fi

# Load .env into the environment (auto-export everything assigned while sourcing).
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

# Fail fast on unfilled placeholders rather than mid-run inside a function host.
REQUIRED_VARS=(
  AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName
  AzureWebJobsStorage
  AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT
  AZURE_SEARCH_SERVICE_ENDPOINT
  AZURE_EMBEDDING_SERVICE_ENDPOINT
  AZURE_EMBEDDING_SERVICE_DEPLOYMENT_NAME
  AZURE_OPENAI_ENDPOINT
  AZURE_OPENAI_CHAT_DEPLOYMENT_NAME
  AZURE_JUDGE_OPENAI_ENDPOINT
  AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME
  RECORD_SCORE_AZURE_INSIGHTS_CONNECTION_STRING
)
for var in "${REQUIRED_VARS[@]}"; do
  value="${!var:-}"
  if [[ -z "${value}" || "${value}" == \<* ]]; then
    echo "ERROR: ${var} is unset or still a placeholder — populate it in ${ENV_FILE}." >&2
    exit 1
  fi
done

echo "[run-integration-test] module: ${MODULE_DIR}"
echo "[run-integration-test] storage account: ${AI_RAG_SERVICE_STORAGE_ACCOUNT_CONNECTION_STRING__accountName}"
echo "[run-integration-test] ensure you have run 'az login' (DefaultAzureCredential)."

cd "${REPO_ROOT}"

# The tests start each function from its target/azure-functions directory, so the apps must
# be packaged from the current sources first (SKIP_BUILD=true reuses existing packages).
if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  echo "[run-integration-test] packaging function apps (SKIP_BUILD=true to reuse existing)..."
  mvn -q clean install -DskipTests
fi

mvn verify -P ai-rag-integration-test -pl "${MODULE}"
