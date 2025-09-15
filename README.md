# CP AI RAG Service

A multi-module Azure Functions project for AI-powered Retrieval-Augmented Generation (RAG) service.
This service processes documents through ingestion, retrieval, and scoring workflows using Azure AI services.

## Architecture

This mono-repo contains four independently deployable Azure Functions:

### 1. AI Document Metadata Check Function
- **Purpose**: Validates document metadata when a new blob is uploaded and enqueues for processing
- **Module**: `ai-document-metadata-check-function`

### 2. AI Document Ingestion Function
- **Purpose**: Orchestrates document preprocessing, chunking, embedding generation, and vector database storage
- **Module**: `ai-document-ingestion-function`

### 3. AI Document Answer Retrieval Function
- **Purpose**: Processes client queries, performs retrieval/grounding, and generates answer summaries
- **Module**: `ai-document-answer-retrieval-function`

### 4. AI Document Answer Scoring Function
- **Purpose**: Scores generated responses and records telemetry in Azure Monitor
- **Module**: `ai-document-answer-scoring-function`

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Azure CLI (for deployment)
- Azure Functions Core Tools (for local development)

## Security Guidelines

**IMPORTANT**: This repository is public and must not contain any secrets or sensitive information.

### Configuration Management
- All sensitive configuration is managed through environment variables or Azure Key Vault
- Sample configuration files (`local.settings.sample.json`) are provided with placeholder values
- Never commit `local.settings.json` files with real values
- Use Azure Key Vault for production secrets

### Files Excluded from Git
The following files and patterns are excluded from version control:
- `local.settings.json` (contains real secrets)
- `target/` directories (build artifacts)
- `*.key`, `*.pem`, `*.p12`, `*.pfx` (certificate files)
- `.env` files (environment-specific secrets)

## Local Development Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd cp-ai-rag-service
   ```

2. **Create local configuration files**
   ```bash
   # Copy sample configuration files for each function
   cp ai-document-metadata-check-function/Azure/local.settings.sample.json ai-document-metadata-check-function/Azure/local.settings.json
   cp ai-document-ingestion-function/Azure/local.settings.sample.json ai-document-ingestion-function/Azure/local.settings.json
   cp ai-document-answer-retrieval-function/Azure/local.settings.sample.json ai-document-answer-retrieval-function/Azure/local.settings.json
   cp ai-document-answer-scoring-function/Azure/local.settings.sample.json ai-document-answer-scoring-function/Azure/local.settings.json
   ```

3. **Configure your local settings**
   Edit each `local.settings.json` file and replace placeholder values with your actual Azure service credentials:
    - Azure Storage connection strings
    - Azure Search endpoint and API key
    - Azure OpenAI endpoint, API key, and deployment name
    - Application Insights instrumentation key
    - Azure Monitor connection string

4. **Build the project**
   ```bash
   mvn clean compile
   ```

5. **Run individual functions locally**
   ```bash
   # Navigate to a specific function directory
   cd ai-document-metadata-check-function
   
   # Run the function locally
   mvn azure-functions:run
   ```

## Building and Testing

### Build All Modules
```bash
mvn clean compile
```

### Run Tests
```bash
mvn test
```

### Package for Deployment
```bash
mvn clean package
```

## Deployment

Each function can be deployed independently using environment-specific naming:

```bash
# Deploy a specific function to development environment
cd ai-document-metadata-check-function
mvn azure-functions:deploy -DazureEnv=dev

# Deploy to staging environment
mvn azure-functions:deploy -DazureEnv=stg

# Deploy to production environment
mvn azure-functions:deploy -DazureEnv=prod
```

### Deployment Order

Since all functions share the same resource group and app service plan, deploy them in this order:

1. **First Function** (creates resource group and app service plan):
   ```bash
   cd ai-document-metadata-check-function
   mvn azure-functions:deploy -DazureEnv=dev
   ```

2. **Subsequent Functions** (use existing resources):
   ```bash
   cd ai-document-ingestion-function
   mvn azure-functions:deploy -DazureEnv=dev
   
   cd ai-document-answer-retrieval-function
   mvn azure-functions:deploy -DazureEnv=dev
   
   cd ai-document-answer-scoring-function
   mvn azure-functions:deploy -DazureEnv=dev
   ```

### Naming Convention

All Azure Functions follow a consistent naming pattern:
- **Function App Name**: `fa-{azureEnv}-ai-document-{function-name}`
- **Resource Group**: `RG-{azureEnv}-CPAIRAGSERVICE` (shared across all functions)
- **App Service Plan**: `ASP-{azureEnv}-CPAIRAGSERVICE` (shared across all functions)

Where `{azureEnv}` is the environment (dev, stg, prod) and `{function-name}` is:
- `metadata-check` - Document metadata validation
- `ingestion` - Document processing orchestration
- `answer-retrieval` - Query processing and retrieval
- `answer-scoring` - Response scoring and telemetry

**Examples:**
- Development: `fa-dev-ai-document-metadata-check`
- Staging: `fa-stg-ai-document-ingestion`
- Production: `fa-prod-ai-document-answer-retrieval`

**Important**: All functions in the same environment share the same resource group and app service plan. This means:
- Only one function needs to create the resource group and app service plan
- Subsequent function deployments will use the existing shared resources
- This reduces costs and simplifies resource management

### Environment-Specific Configuration

Each environment should have its own configuration:

1. **Development Environment** (`-DazureEnv=dev`):
    - Function App: `fa-dev-ai-document-{function-name}`
    - Resource Group: `RG-dev-CPAIRAGSERVICE`
    - Lower cost tier for development

2. **Staging Environment** (`-DazureEnv=stg`):
    - Function App: `fa-stg-ai-document-{function-name}`
    - Resource Group: `RG-stg-CPAIRAGSERVICE`
    - Production-like configuration for testing

3. **Production Environment** (`-DazureEnv=prod`):
    - Function App: `fa-prod-ai-document-{function-name}`
    - Resource Group: `RG-prod-CPAIRAGSERVICE`
    - High availability and performance configuration

### Required Azure Resources

Before deployment, ensure you have the following Azure resources for each environment:
- Azure Storage Account (for blob storage and queues)
- Azure Search Service
- Azure OpenAI Service
- Application Insights
- Azure Monitor (for telemetry)

## Configuration Reference

### Environment Variables

Each function requires specific configuration values:

#### Common Variables
- `AzureWebJobsStorage`: Azure Storage connection string for Functions runtime
- `FUNCTIONS_WORKER_RUNTIME`: Set to "java"
- `FUNCTIONS_EXTENSION_VERSION`: Set to "~4"
- `APPINSIGHTS_INSTRUMENTATIONKEY`: Application Insights instrumentation key

#### Function-Specific Variables

**Metadata Check Function:**
- `AZURE_STORAGE_CONNECTION_STRING`: Storage account connection string
- `PROCESSING_QUEUE_NAME`: Queue name for document processing

**Ingestion Function:**
- `AZURE_STORAGE_CONNECTION_STRING`: Storage account connection string
- `PROCESSING_QUEUE_NAME`: Queue name for document processing
- `AZURE_SEARCH_ENDPOINT`: Azure Search service endpoint
- `AZURE_SEARCH_API_KEY`: Azure Search API key
- `AZURE_OPENAI_ENDPOINT`: Azure OpenAI service endpoint
- `AZURE_OPENAI_API_KEY`: Azure OpenAI API key
- `AZURE_OPENAI_DEPLOYMENT_NAME`: Azure OpenAI deployment name

**Answer Retrieval Function:**
- `AZURE_SEARCH_ENDPOINT`: Azure Search service endpoint
- `AZURE_SEARCH_API_KEY`: Azure Search API key
- `AZURE_OPENAI_ENDPOINT`: Azure OpenAI service endpoint
- `AZURE_OPENAI_API_KEY`: Azure OpenAI API key
- `AZURE_OPENAI_DEPLOYMENT_NAME`: Azure OpenAI deployment name
- `SCORING_QUEUE_NAME`: Queue name for answer scoring

**Answer Scoring Function:**
- `SCORING_QUEUE_NAME`: Queue name for answer scoring
- `AZURE_MONITOR_CONNECTION_STRING`: Azure Monitor connection string

## Development Guidelines

1. **Code Organization**: Each function is a separate Maven module with its own dependencies
2. **Common Code**: Shared utilities and models are in the `libs/common` module
3. **Testing**: Each module includes unit tests in the `src/test/java` directory
4. **Configuration**: Use the `ConfigurationUtil` class for accessing configuration values
5. **Logging**: Use SLF4J for logging throughout the application

## Contributing

1. Ensure all tests pass before submitting changes
2. Never commit secrets or sensitive configuration
3. Update documentation for any new configuration requirements
4. Follow the existing code structure and naming conventions

