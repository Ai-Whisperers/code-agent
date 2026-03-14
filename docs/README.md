# Code Agent Runner

The Code Agent Runner is a self-hosted AI-powered automation tool that handles code fixes, dependency upgrades, pull request reviews, and documentation generation. It integrates with JIRA, multiple git platforms (Bitbucket, Azure DevOps, GitLab), and external services like Aikido Security and Confluence.

## What It Does

- **Automated Code Fixes**: Clones repositories, analyzes JIRA tickets, applies AI-powered fixes, validates changes with Maven, and creates pull requests
- **Vulnerability Fixes**: Integrates with Aikido Security to automatically resolve dependency vulnerabilities with enriched context
- **Code Review**: AI-powered pull request reviews checking security, design, quality, testing, and best practices
- **Test Generation**: Automatically generates unit tests for untested code paths
- **Documentation Generation**: Creates comprehensive project documentation with architecture diagrams
- **Multi-Platform Support**: Works with Bitbucket, Azure DevOps, and GitLab
- **Semantic Code Search**: Vector-based code search using pgvector and Voyage AI embeddings

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](architecture.md) | High-level system design, components, and tech stack |
| [API Documentation](api.md) | Complete REST API reference with endpoints and schemas |
| [Data Model](data-model.md) | Database schema, tables, and relationships |
| [Getting Started](getting-started.md) | Developer setup, build instructions, and project walkthrough |
| [Key Business Flows](flows.md) | Important workflows with sequence diagrams |
| [Configuration Reference](configuration.md) | All configuration properties and environment variables |

## Quick Start

```bash
# Prerequisites: JDK 17+, Docker, PostgreSQL with pgvector extension

# 1. Clone and build
git clone <repository-url>
cd code-agent-runner
./mvnw clean package

# 2. Set up database
docker run -d --name postgres-pgvector \
  -e POSTGRES_DB=code_agent \
  -e POSTGRES_USER=code_agent \
  -e POSTGRES_PASSWORD=yourpassword \
  -p 5432:5432 \
  pgvector/pgvector:pg17

# 3. Configure environment variables
export ANTHROPIC_API_KEY="your-api-key"
export DATABASE_PASSWORD="yourpassword"
export JIRA_BASE_URL="https://yourcompany.atlassian.net"
export JIRA_USER="your-email"
export JIRA_API_TOKEN="your-token"

# 4. Run in development mode
./mvnw quarkus:dev

# 5. Access Swagger UI
open http://localhost:8080/q/swagger-ui
```

The application will start on port 8080 and automatically run database migrations. See the [Getting Started guide](getting-started.md) for detailed setup instructions.

## Key Features

- **Multi-Language Support**: Java (Maven), .NET, Node.js projects
- **Security Integration**: Built-in linting with Checkstyle, PMD, SpotBugs, ESLint
- **Scalable Architecture**: Async job processing with configurable concurrency
- **Enterprise Integration**: JIRA workflows, Microsoft Teams notifications, n8n webhooks
- **Code Intelligence**: AST analysis, dependency graphs, semantic search
- **Compliance**: Configurable guardrails and blocked paths for security

## Technology Stack

- **Framework**: Quarkus 3.17.8 (Java 17)
- **Database**: PostgreSQL with pgvector extension
- **AI**: Anthropic Claude with tool use
- **Security**: Aikido Security integration
- **Documentation**: Confluence Cloud publishing
- **Git Platforms**: Bitbucket, Azure DevOps, GitLab support