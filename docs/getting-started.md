# Getting Started

This guide will help you set up and configure the Code Agent Runner for development or production use. Follow these steps to get the system running and integrated with your development workflow.

## Prerequisites

Before setting up the Code Agent Runner, ensure you have the following:

### Software Requirements

| Component | Minimum Version | Recommended | Notes |
|-----------|----------------|-------------|-------|
| **Java** | 21 | OpenJDK 21 LTS | Eclipse Temurin or similar distribution |
| **Maven** | 3.8.0 | Latest 3.9.x | Build automation and dependency management |
| **PostgreSQL** | 14.0 | 15.0+ | With pgvector extension for semantic search |
| **Git** | 2.30.0 | Latest | For repository operations |
| **Docker** | 20.10.0 | Latest | For containerized deployment (optional) |

### Optional Components

| Component | Purpose | Notes |
|-----------|---------|-------|
| **Node.js** | ESLint integration | Version 18.x or 20.x |
| **.NET SDK** | C# code formatting, build validation, and coverage | Version 9.0 (matches the Docker image). Required for `dotnet format`, `dotnet test`, and Coverlet coverage. |
| **Chromium** | Mermaid diagram rendering | Headless browser for SVG generation |

### External Service Accounts

You'll need accounts and API credentials for:

- **Anthropic** - Claude API access for AI functionality
- **JIRA Cloud** - Issue management integration 
- **Git Platform** - Bitbucket Cloud, Azure DevOps, or GitLab
- **Aikido Security** - Vulnerability management (optional)
- **Voyage AI** - Vector embeddings for semantic search (optional)

## Environment Setup

### 1. Local Development Environment

#### Install Java 21
```bash
# macOS with Homebrew
brew install openjdk@21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# Verify installation
java -version
```

#### Install PostgreSQL with pgvector
```bash
# macOS with Homebrew
brew install postgresql@15
brew services start postgresql@15

# Install pgvector extension
cd /tmp
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector
export PG_CONFIG=/opt/homebrew/opt/postgresql@15/bin/pg_config
make && make install

# Ubuntu/Debian
sudo apt install postgresql postgresql-contrib
sudo systemctl start postgresql

# Build pgvector from source
sudo apt install postgresql-server-dev-15 build-essential
cd /tmp && git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git
cd pgvector && make && sudo make install
```

#### Create Database
```sql
-- Connect as postgres user
createdb code_agent
psql code_agent

-- Create user and grant permissions
CREATE USER code_agent WITH PASSWORD 'secure_password';
GRANT ALL PRIVILEGES ON DATABASE code_agent TO code_agent;

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. Configuration

#### Environment Variables

Create a `.env` file in the project root with required configuration:

```bash
# Core Configuration
ANTHROPIC_API_KEY=sk-ant-api03-...
ANTHROPIC_MODEL=claude-sonnet-4-20250514

# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/code_agent
DATABASE_USER=code_agent
DATABASE_PASSWORD=secure_password

# JIRA Cloud
JIRA_BASE_URL=https://your-domain.atlassian.net
JIRA_USER=your-email@company.com
JIRA_API_TOKEN=ATATT3xFFGF0...
JIRA_AGENT_ASSIGNEE=Code Agent

# Git Platform (choose one)
GIT_PLATFORM=bitbucket

# Bitbucket Cloud
BITBUCKET_WORKSPACE=your-workspace
BITBUCKET_USER=your-username
BITBUCKET_APP_PASSWORD=ATCTT3xFFGF0...

# OR Azure DevOps
# AZUREDEVOPS_PAT=your-pat-token
# AZUREDEVOPS_AGENT_USER=Code Agent

# OR GitLab
# GITLAB_TOKEN=glpat-...
# GITLAB_AGENT_USER=Code Agent

# Optional: Semantic Search — Bedrock uses the AWS credential chain (no API key needed).
# For local dev, configure ~/.aws/credentials or set AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY.

# Optional: Security
API_KEY=your-api-key
WEBHOOK_SECRET_BITBUCKET=your-webhook-secret
```

#### Getting API Credentials

**Anthropic API Key:**
1. Visit [console.anthropic.com](https://console.anthropic.com)
2. Sign up/login and navigate to API Keys
3. Create a new key and copy the value

**JIRA API Token:**
1. Go to [id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens)
2. Click "Create API token"
3. Give it a descriptive label and copy the token

**Bitbucket App Password:**
1. Go to Bitbucket Settings > Personal settings > App passwords
2. Create app password with Repository (Read, Write) and Pull requests (Read, Write) permissions
3. Copy the generated password

**Azure DevOps PAT:**
1. Go to Azure DevOps > User settings > Personal access tokens
2. Create new token with Code (read & write) and Pull Request (read & write) scopes
3. Copy the token value

### 3. Build and Run

#### Development Mode
```bash
# Clone the repository
git clone <repository-url>
cd code-agent-runner

# Install dependencies and run in development mode
mvn quarkus:dev
```

This starts the application with:
- Hot reload enabled
- Database migrations applied automatically
- API available at `http://localhost:8080`
- Swagger UI at `http://localhost:8080/q/swagger-ui`

#### Production Build
```bash
# Build the application
mvn clean package -DskipTests

# Run the packaged application
java -jar target/quarkus-app/quarkus-run.jar
```

#### Docker Deployment
```bash
# Build Docker image
docker build -t code-agent-runner .

# Run with environment variables
docker run -p 8080:8080 \
  --env-file .env \
  code-agent-runner
```

## Project Structure

Understanding the codebase layout will help you navigate and extend the system:

```
code-agent-runner/
├── src/main/java/com/eneve/agent/
│   ├── RunFixResource.java          # Main REST endpoints
│   ├── agent/                       # Core agent logic
│   │   ├── AgentRunner.java         # Job orchestrator
│   │   ├── ClaudeToolUseLoop.java   # AI interaction loop
│   │   ├── JobQueue.java            # Concurrent job processing
│   │   └── *Store.java              # Data persistence layers
│   ├── scm/                         # Git platform integrations
│   │   ├── bitbucket/               # Bitbucket Cloud support
│   │   ├── azuredevops/            # Azure DevOps support
│   │   └── gitlab/                  # GitLab support
│   ├── webhooks/                    # Webhook handlers
│   ├── tools/                       # AI tool implementations
│   ├── linter/                      # Static analysis integration
│   └── model/                       # Data transfer objects
├── src/main/resources/
│   ├── application.properties       # Configuration defaults
│   └── db/migration/               # Database schema migrations
├── src/test/java/                  # Unit and integration tests
├── docs/                           # Documentation (this folder)
├── Dockerfile                      # Container build definition
├── settings.xml                    # Maven settings with Nexus support
└── pom.xml                         # Maven project definition
```

### Key Packages

- **`agent/`**: Core business logic for job processing and AI interaction
- **`scm/`**: Pluggable git platform integrations (Bitbucket, Azure DevOps, GitLab)
- **`webhooks/`**: Handlers for incoming webhooks from external systems
- **`tools/`**: AI tool implementations (file operations, code search, etc.)
- **`model/`**: Request/response DTOs and data models

## First Steps

### 1. Verify Installation

Test your setup by checking the health endpoint:

```bash
curl http://localhost:8080/health

# Expected response:
{
  "status": "UP",
  "availableSlots": 3,
  "runningJobs": 0,
  "queuedJobs": 0
}
```

### 2. Configure Repository Settings

Enable automated review for a test repository:

```bash
curl -X PUT http://localhost:8080/settings/repos/your-workspace/test-repo \
  -H "Content-Type: application/json" \
  -d '{
    "reviewEnabled": true,
    "vectorEnabled": false,
    "ruleNames": ["java-conventions"]
  }'
```

### 3. Submit a Test Job

Try the quick-fix endpoint with a JIRA ticket:

```bash
curl -X POST http://localhost:8080/quick-fix \
  -H "Content-Type: application/json" \
  -d '{
    "jiraKey": "PROJ-123",
    "repoUrl": "https://bitbucket.org/workspace/repo.git"
  }'

# Response includes jobId for status polling:
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "branch": "agent/PROJ-123-fix-issue"
}
```

### 4. Poll Job Status

Monitor the job progress:

```bash
curl http://localhost:8080/status/550e8400-e29b-41d4-a716-446655440000

# Status progression: QUEUED → RUNNING → AWAITING_APPROVAL → APPROVED
```

## Integration Setup

### JIRA Webhook

Set up automatic job triggering when issues are assigned to the agent:

1. **Create Agent User**: Add a "Code Agent" user in JIRA
2. **Configure Webhook**: Go to JIRA Settings > System > Webhooks
   - URL: `https://your-agent-host/webhooks/jira`
   - Events: Issue created, Issue updated
   - JQL: `assignee = "Code Agent"`

3. **Environment Variables**:
   ```bash
   JIRA_AGENT_ASSIGNEE=Code Agent
   JIRA_AGENT_DEFAULT_REPO_URL=https://bitbucket.org/workspace/main-repo.git
   ```

### Git Platform Webhooks

#### Bitbucket Cloud

1. **Repository Webhooks**: Go to Repository Settings > Webhooks
2. **Add PR Review Webhook**:
   - URL: `https://your-agent-host/webhooks/bitbucket/pull-request`
   - Events: Pull Request Created, Pull Request Updated
   - Secret: Set `WEBHOOK_SECRET_BITBUCKET`

3. **Add Comment Webhook**:
   - URL: `https://your-agent-host/webhooks/bitbucket/pull-request-comment`
   - Events: Pull Request Comment Created

#### Azure DevOps

1. **Service Hooks**: Go to Project Settings > Service Hooks
2. **Web Hook Subscription**:
   - Event: Pull request created, Pull request updated
   - URL: `https://your-agent-host/webhooks/azuredevops/pull-request`

## Advanced Configuration

### Semantic Search Setup

Enable vector-based code search across repositories:

1. **Grant Bedrock permissions** to the ECS task role (or IAM user for local dev):
   ```json
   {
     "Effect": "Allow",
     "Action": ["bedrock:InvokeModel", "bedrock:Rerank"],
     "Resource": "arn:aws:bedrock:eu-central-1::foundation-model/*"
   }
   ```
   No API key is required — the AWS credential chain handles authentication automatically.

2. **Enable for Repository**:
   ```bash
   curl -X PATCH http://localhost:8080/settings/repos/workspace/repo/vector/enable
   ```

4. **Build Code Graph**:
   ```bash
   curl -X POST http://localhost:8080/graph/rebuild/workspace/repo
   ```

### Coding Rules Integration

Set up shared coding standards via Cursor rules:

1. **Create Rules Repository**: Store `.cursor/rules/*.mdc` files
2. **Configure Default Repository**:
   ```bash
   RULES_REPO_URL=https://bitbucket.org/workspace/cursor-rules.git
   ```

3. **Apply Rules to Jobs**:
   ```json
   {
     "ruleNames": ["java-conventions", "security-standards"],
     "extraRules": "Always validate input parameters"
   }
   ```

### Static Analysis Integration

Enable automated linting during code reviews:

```bash
# Enable specific linters
LINTER_ENABLED=true
LINTER_CHECKSTYLE_ENABLED=true
LINTER_PMD_ENABLED=true
LINTER_SPOTBUGS_ENABLED=true
LINTER_ESLINT_ENABLED=true

# Configure behavior
LINTER_MAX_FIX_ITERATIONS=2
LINTER_FAIL_ON_NEW_ISSUES=false
```

### Security Hardening

Production security configurations:

```bash
# API Protection
API_KEY=secure-random-key

# Webhook Security
WEBHOOK_SECRET_BITBUCKET=secure-webhook-secret
WEBHOOK_SECRET_AZUREDEVOPS=secure-webhook-secret
WEBHOOK_SECRET_JIRA=secure-webhook-secret

# Guardrails
RUN_FIX_BLOCKED_PATHS=src/main/security,src/main/billing,.github,.env
RUN_FIX_ALLOWED_COMMANDS=mvn,git diff,git status,ls,find,cat,grep
RUN_FIX_MAX_FILES_CHANGED=10
RUN_FIX_MAX_LINES_CHANGED=500
```

## Production Deployment

### AWS ECS Fargate

For production deployment on AWS, see the detailed AWS section in the main README. Key considerations:

1. **Use Secrets Manager** for API keys and tokens
2. **Deploy in Private Subnets** with NAT gateway for outbound access
3. **Configure ALB** with HTTPS termination
4. **Set Up RDS PostgreSQL** with pgvector extension
5. **Use ECR** for Docker image storage

### Health Checks

Configure health check endpoints:

- **Liveness**: `GET /q/health/live` - Application is running
- **Readiness**: `GET /q/health/ready` - Application is ready for traffic
- **Custom**: `GET /health` - Includes job queue status

### Monitoring

Key metrics to monitor:

- **Job Queue Depth**: Indicates system load
- **AI API Costs**: Track via `GET /stats/ai-calls/summary`
- **Review Quality**: Monitor via `GET /metrics/review-quality/{workspace}/{repo}`
- **Database Performance**: Query times and connection pool usage

## Troubleshooting

### Common Issues

**PostgreSQL Connection Failed**
```bash
# Check database connection
psql -h localhost -U code_agent -d code_agent

# Verify pgvector extension
SELECT * FROM pg_extension WHERE extname = 'vector';
```

**AI API Rate Limits**
```bash
# Check API call history
curl http://localhost:8080/stats/ai-calls?from=2024-01-15

# Adjust rate limiting if needed
ANTHROPIC_MAX_TOKENS=4096
```

**Git Authentication Issues**
```bash
# Test git credentials
git clone https://bitbucket.org/workspace/repo.git

# Verify app password permissions
# Must include: Repository (Read, Write), Pull requests (Read, Write)
```

### Log Analysis

Enable debug logging for troubleshooting:

```bash
# Application properties
quarkus.log.category."com.eneve.agent".level=DEBUG

# Or environment variable
export QUARKUS_LOG_CATEGORY_COM_ENEVE_AGENT_LEVEL=DEBUG
```

Common log patterns to look for:
- `Job xyz accepted` - Successful job submission
- `Claude tool-use loop iteration` - AI processing progress
- `Build validation passed` - Successful code compilation
- `PR created` - Successful pull request generation

### Getting Help

- **Documentation**: Check other sections of this documentation
- **API Reference**: Use Swagger UI at `/q/swagger-ui`
- **Health Status**: Monitor `/health` endpoint
- **Logs**: Check application logs for detailed error messages

## Next Steps

Once you have the basic setup working:

1. **Configure Additional Repositories**: Enable review and vector indexing
2. **Set Up Automation Hooks**: Customize triggers for repository events
3. **Integrate with n8n**: Set up approval workflows
4. **Configure Teams Notifications**: Get notified of job completions
5. **Optimize Performance**: Tune job queue and database settings

Refer to the [Configuration Reference](configuration.md) for detailed settings and the [API Documentation](api.md) for endpoint specifications.