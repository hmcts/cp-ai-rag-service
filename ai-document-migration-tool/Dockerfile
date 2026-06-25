# Runtime-only image for the one-off index-migration tool.
#
# The jar + dependency lib/ are built ON THE HOST first (the Maven build resolves through the internal
# Artifactory mirror configured in ~/.m2/settings.xml, which a clean in-image build / cloud build agent
# cannot reach). This image just packages those prebuilt artifacts into a clean JRE.
#
# Build context is THIS module directory (so target/ is available):
#   mvn -pl ai-document-migration-tool -am -DskipTests package
#   docker build -f ai-document-migration-tool/Dockerfile -t ai-index-migration:1 ai-document-migration-tool
#
# The image is deliberately clean: no Azure CLI, no secrets, no mounts. Authentication is resolved at
# run time by DefaultAzureCredential — locally via a service principal in the environment
# (AZURE_TENANT_ID / AZURE_CLIENT_ID / AZURE_CLIENT_SECRET), and in an Azure Container Apps Job via the
# attached managed identity. The same image therefore runs unchanged in both places.

FROM crmdvrepo01.azurecr.io/docker.io/library/eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root runtime user (fixed high uid; not --system, whose uids must stay below 1000).
RUN useradd --uid 10001 --create-home appuser

# Thin application jar (its manifest carries Main-Class + a lib/-relative Class-Path) plus the
# dependency jars. Keeping dependencies as separate jars preserves each one's META-INF/services, so
# the Azure SDK's ServiceLoader-based HTTP client / credential discovery works without uber-jar merging.
COPY target/lib /app/lib
COPY target/ai-document-migration-tool-*.jar /app/app.jar

USER appuser

# The entrypoint is the MigrationTool dispatcher (jar Main-Class = uk.gov.moj.cp.migration.MigrationTool).
# It takes the tool name as its FIRST argument, then that tool's own args — there is no default tool.
# Args are supplied at run/Job-start time, e.g.:
#   index <endpoint> <sourceIndex> <targetIndex> <aliasName> <schemaResourcePath> [workers] [maxRecords] [startAfterId]
#   table <sourceTable> <targetTable> [partitionKeyOverride] [maxRecords]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
