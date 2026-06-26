# Runtime-only image for the migration tool.
#
# The artefact is a single distribution archive — the thin app jar + its dependency jars under lib/,
# bundled into one tar.gz by maven-assembly-plugin. It is built ON THE HOST / in CI (Maven resolves
# through the internal Artifactory mirror, which a clean in-image build cannot reach) and either taken
# from target/ locally or pulled from Artifactory in CI. This image just unpacks it into a clean JRE.
#
# Build the artefact, then the image (build context = this module dir):
#   mvn -pl ai-document-migration-tool -am -DskipTests package
#   docker build -f ai-document-migration-tool/Dockerfile -t ai-rag-migration:1 ai-document-migration-tool
# In CI, download the dist tar.gz from Artifactory into the build context and override the path:
#   docker build --build-arg DIST_ARCHIVE=ai-document-migration-tool-<ver>-dist.tar.gz ...
#
# The image is deliberately clean: no Azure CLI, no secrets, no mounts. Authentication is resolved at
# run time by DefaultAzureCredential — locally via a service principal in the environment
# (AZURE_TENANT_ID / AZURE_CLIENT_ID / AZURE_CLIENT_SECRET), and in an Azure Container Apps Job via the
# attached managed identity. The same image therefore runs unchanged in both places.

FROM crmdvrepo01.azurecr.io/docker.io/library/eclipse-temurin:21-jre AS runtime
WORKDIR /app
ARG VERSION
# Non-root runtime user (fixed high uid; not --system, whose uids must stay below 1000).
RUN useradd --uid 10001 --create-home appuser

# Single distribution archive: ADD auto-extracts a local tar.gz, yielding /app/app.jar + /app/lib/.
# Bundling (not shading) keeps each dependency a separate jar, so the Azure SDK's ServiceLoader-based
# HTTP client / serializer discovery and the signed Azure jars are unaffected. The app jar's manifest
# Class-Path resolves lib/ relative to itself. DIST_ARCHIVE defaults to the local build; CI overrides it.
ARG DIST_ARCHIVE_URL=https://libraries-internal.mdv.cpp.nonlive/artifactory/repocentral/uk/gov/moj/cp/azure/ragservice/ai-document-migration-tool/19.0.1/ai-document-migration-tool-*-dist.tar.gz
RUN curl "${DIST_ARCHIVE_URL}" | tar -xzf - -C /app/

USER appuser

# The entrypoint is the MigrationTool dispatcher (jar Main-Class = uk.gov.moj.cp.migration.MigrationTool).
# It takes the tool name as its FIRST argument, then that tool's own args — there is no default tool.
# Args are supplied at run/Job-start time, e.g.:
#   index <endpoint> <sourceIndex> <targetIndex> <aliasName> <schemaResourcePath> [workers] [maxRecords] [startAfterId]
#   table <sourceTable> <targetTable> [partitionKeyOverride] [maxRecords]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
