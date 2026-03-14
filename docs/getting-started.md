# Getting Started

This guide walks you through setting up a development environment for the Code Agent Runner, from initial prerequisites to running your first automation job.

## Prerequisites

### Required Software
- **JDK 17+** - OpenJDK or Oracle JDK
- **Docker** - For database and containerized dependencies  
- **Git** - Version control operations
- **PostgreSQL 13+** - With pgvector extension

### Optional Development Tools
- **IDE**: IntelliJ IDEA, VS Code, or Eclipse with Java support
- **Database Client**: pgAdmin, DBeaver, or similar for database inspection
- **REST Client**: Postman, Insomnia, or curl for API testing

### External Service Accounts
Set up accounts for the integrations you plan to use:

- **Anthropic** - Claude AI API access ([console.anthropic.com](https://console.anthropic.com))
- **JIRA Cloud** - For issue tracking integration
- **Git Platform** - Bitbucket Cloud, Azure DevOps, or GitLab
- **Aikido Security** (Optional) - For vulnerability management
- **Confluence Cloud** (Optional) - For documentation publishing
- **Voyage AI** (Optional) - For semantic code search

## Environment Setup

### 1. Clone Repository
```bash
git clone <repository-url>
cd code-agent-runner
```

### 2. Database Setup

#### Option A: Docker (Recommended for Development)
```bash
# Start PostgreSQL with pgvector extension
docker run -d --name postgres-pgvector \
  -e POSTGRES_DB=code_agent \
  -e POSTGRES_USER=code_agent \
  -e POSTGRES_PASSWORD=your_secure_password \
  -p 5432:5432 \
  pgvector/pgvector:pg17

# Verify connection
docker exec postgres-pgvector psql -U code_agent -d code_agent -c "SELECT version();"
```

#### Option B: Local PostgreSQL Installation
```bash
# Install pgvector extension (varies by OS)
# Ubuntu/Debian:
sudo apt install postgresql-17-pgvector

# macOS with Homebrew:
brew install pgvector

# Create database and user
sudo -u postgres psql << EOF
CREATE DATABASE code_agent;
CREATE USER code_agent WITH ENCRYPTED PASSWORD 'your_secure_password';
GRANT ALL PRIVILEGES ON DATABASE code_agent TO code_agent;
\q
EOF

# Enable pgvector extension
sudo -u postgres psql -d code_agent -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 3. Environment Configuration

Create a `.env` file in the project root with your configuration:

```bash
# Required: AI Integration
ANTHROPIC_API_KEY=your-anthropic-api-key

# Required: Database
DATABASE_PASSWORD=your_secure_password

# Required: JIRA Integration  
JIRA_BASE_URL=https://yourcompany.atlassian.net
JIRA_USER=your-email@company.com
JIRA_API_TOKEN=your-jira-api-token

# Required: Git Platform (choose one)
GIT_PLATFORM=bitbucket

# For Bitbucket:
BITBUCKET_WORKSPACE=your-bitbucket-workspace
BITBUCKET_USER=your-username
BITBUCKET_APP_PASSWORD=your-app-password

# For Azure DevOps:
AZUREDEVOPS_BASE_URL=https://dev.azure.com/your-org
AZUREDEVOPS_PAT=your-personal-access-token
AZUREDEVOPS_AGENT_USER=agent-user

# For GitLab:
GITLAB_TOKEN=your-gitlab-token
GITLAB_AGENT_USER=agent-user

# Optional: Security
API_KEY=your-api-key-for-rest-endpoints

# Optional: Webhook Security
WEBHOOK_SECRET_BITBUCKET=your-webhook-secret
WEBHOOK_SECRET_JIRA=your-webhook-secret

# Optional: Notifications
TEAMS_WEBHOOK_URL=https://your-teams-webhook-url
N8N_WEBHOOK_URL=https://your-n8n-webhook-url

# Optional: Aikido Security
AIKIDO_CLIENT_ID=your-aikido-client-id
AIKIDO_CLIENT_SECRET=your-aikido-client-secret

# Optional: Confluence Publishing
CONFLUENCE_BASE_URL=https://yourcompany.atlassian.net/wiki
CONFLUENCE_USER=your-email@company.com
CONFLUENCE_API_TOKEN=your-confluence-api-token

# Optional: Semantic Search
VOYAGE_API_KEY=your-voyage-ai-api-key
```

### 4. Build and Test

```bash
# Build the project
./mvnw clean compile

# Run tests  
./mvnw test

# Package application
./mvnw package
```

### 5. Run in Development Mode

```bash
# Start with hot reload
./mvnw quarkus:dev
```

The application will start on `http://localhost:8080` with:
- **Swagger UI**: `http://localhost:8080/q/swagger-ui`
- **Health Check**: `http://localhost:8080/q/health`
- **Development UI**: `http://localhost:8080/q/dev`

## Project Structure

```
code-agent-runner/
├── src/
│   ├── main/
│   │   ├── java/com/eneve/agent/
│   │   │   ├── agent/              # Core AI agent logic
│   │   │   ├── bitbucket/         # Bitbucket integration
│   │   │   ├── scm/               # SCM service abstraction
│   │   │   ├── jira/              # JIRA integration  
│   │   │   ├── tools/             # AI tool implementations
│   │   │   ├── model/             # Data models and DTOs
│   │   │   ├── webhooks/          # Webhook handlers
│   │   │   ├── workspace/         # Git workspace management
│   │   │   ├── diff/              # Code diff analysis
│   │   │   ├── linter/            # Code quality tools
│   │   │   ├── aikido/            # Security integration
│   │   │   └── *.java            # REST controllers
│   │   └── resources/
│   │       ├── db/migration/      # Database schema
│   │       └── application.properties
│   └── test/                      # Unit and integration tests
├── docs/                          # Project documentation
├── pom.xml                        # Maven configuration
├── Dockerfile                     # Container build
└── README.md                      # Basic project info
```

### Key Packages

| Package | Description |
|---------|-------------|
| `agent` | Core AI agent runner and job queue |
| `tools` | AI tool registry and implementations |
| `scm` | Git platform abstraction layer |
| `model` | Request/response models and entities |
| `webhooks` | Platform-specific webhook handlers |
| `workspace` | Git repository management |
| `jira` | JIRA API integration |
| `aikido` | Security vulnerability management |
| `linter` | Code quality and static analysis |

## First Steps

### 1. Verify Installation
```bash
# Check health endpoint
curl http://localhost:8080/health

# Expected response:
{
  "status": "UP",
  "availableSlots": 3,
  "runningJobs": 0,
  "queuedJobs": 0
}
```

### 2. Configure a Repository
```bash
# Enable reviews for a repository
curl -X PUT \
  -H "Content-Type: application/json" \
  -d '{"reviewEnabled": true, "ruleNames": "java-conventions"}' \
  http://localhost:8080/settings/repos/your-workspace/your-repo
```

### 3. Submit Your First Job
```bash
# Quick fix job using JIRA key
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "repoUrl": "https://bitbucket.org/workspace/repo.git",
    "jiraKey": "PROJ-123"
  }' \
  http://localhost:8080/quick-fix
```

### 4. Monitor Job Progress
```bash
# Check job status (use jobId from previous response)
curl http://localhost:8080/status/{jobId}

# Monitor in real-time with watch
watch -n 5 'curl -s http://localhost:8080/status/{jobId} | jq'
```

## Common Configuration

### JIRA Integration Setup
1. Create API token in JIRA: **Account Settings** → **Security** → **API tokens**
2. Test connection:
   ```bash
   curl -H "Authorization: Basic $(echo -n 'your-email:your-token' | base64)" \
        https://yourcompany.atlassian.net/rest/api/2/myself
   ```

### Bitbucket Integration Setup
1. Create app password: **Personal settings** → **App passwords**
2. Required permissions: Repositories (Read/Write), Pull requests (Read/Write)
3. Test connection:
   ```bash
   curl -H "Authorization: Basic $(echo -n 'username:app-password' | base64)" \
        https://api.bitbucket.org/2.0/user
   ```

### Webhook Configuration
Set up webhooks in your git platform to trigger automated reviews:

**Bitbucket Webhooks:**
- URL: `https://your-domain/webhooks/bitbucket/pull-request`
- Events: Pull request created, updated
- URL: `https://your-domain/webhooks/bitbucket/pull-request-comment`  
- Events: Pull request comment created

**JIRA Webhooks:**
- URL: `https://your-domain/webhooks/jira`
- Events: Issue created, updated

## Development Workflow

### Hot Reload Development
```bash
# Start dev mode with automatic reload
./mvnw quarkus:dev

# Make code changes - application reloads automatically
# Database schema changes require restart
```

### Database Operations
```bash
# Connect to database
docker exec -it postgres-pgvector psql -U code_agent -d code_agent

# View migration status
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;

# Reset database (destroys all data)
docker stop postgres-pgvector && docker rm postgres-pgvector
```

### Testing
```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=RunFixResourceTest

# Integration tests with testcontainers
./mvnw test -Dquarkus.test.profile=test
```

### Docker Development
```bash
# Build container
./mvnw package -Dquarkus.container-image.build=true

# Run container
docker run -p 8080:8080 \
  -e DATABASE_URL="jdbc:postgresql://host.docker.internal:5432/code_agent" \
  -e ANTHROPIC_API_KEY="your-key" \
  code-agent-runner:1.0.0-SNAPSHOT
```

## Troubleshooting

### Common Issues

**Database Connection Errors**
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Check database connectivity
docker exec postgres-pgvector pg_isready -U code_agent

# View database logs
docker logs postgres-pgvector
```

**Missing pgvector Extension**
```sql
-- Connect to database and run:
CREATE EXTENSION IF NOT EXISTS vector;
SELECT * FROM pg_extension WHERE extname = 'vector';
```

**JIRA Authentication Issues**
```bash
# Test JIRA credentials
curl -v -H "Authorization: Basic $(echo -n 'email:token' | base64)" \
     https://yourcompany.atlassian.net/rest/api/2/myself
```

**Git Platform Authentication**
```bash
# Test Bitbucket credentials
curl -v -H "Authorization: Basic $(echo -n 'user:password' | base64)" \
     https://api.bitbucket.org/2.0/user

# Test Git clone (should not prompt for credentials)
git clone https://username:password@bitbucket.org/workspace/repo.git /tmp/test-clone
```

### Logging Configuration
```properties
# Add to application.properties for more detailed logging
quarkus.log.category."com.eneve.agent".level=DEBUG
quarkus.log.category."org.hibernate.SQL".level=DEBUG
quarkus.log.category."org.flywaydb".level=DEBUG
```

### Performance Tuning
```properties
# Increase job queue capacity
run-fix.max-concurrent-jobs=5
run-fix.max-queue-size=50

# Adjust worker thread timeout for large repositories
quarkus.vertx.max-worker-execute-time=30m

# Database connection pool tuning
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.min-size=5
```

## Next Steps

1. **Explore the API** - Use Swagger UI to test different endpoints
2. **Set up Webhooks** - Configure automated job triggers from git platforms
3. **Customize Rules** - Create repository-specific coding standards
4. **Monitor Usage** - Check AI costs and job metrics via `/stats/ai-calls`
5. **Scale Up** - Deploy to production with Docker and proper secrets management

For detailed API reference, see the [API Documentation](api.md). For architecture insights, refer to the [Architecture Overview](architecture.md).