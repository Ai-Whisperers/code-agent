# Configuration Reference

The Code Agent Runner is configured entirely through environment variables and application properties. This document provides a comprehensive reference of all available configuration options, organized by functional area.

## Core AI Configuration

### Anthropic Claude API

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `ANTHROPIC_API_KEY` | - | ✅ | Anthropic API key for Claude access |
| `ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | ❌ | Claude model identifier |
| `ANTHROPIC_MAX_TOKENS` | `8192` | ❌ | Maximum tokens per API call |

**Cost Estimation (USD per million tokens):**

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_PRICING_INPUT` | `3.0` | Input token cost |
| `ANTHROPIC_PRICING_OUTPUT` | `15.0` | Output token cost |
| `ANTHROPIC_PRICING_CACHE_WRITE` | `3.75` | Cache creation cost |
| `ANTHROPIC_PRICING_CACHE_READ` | `0.30` | Cache read cost |

**Example:**
```bash
ANTHROPIC_API_KEY=sk-ant-api03-xyz123
ANTHROPIC_MODEL=claude-sonnet-4-20250514
ANTHROPIC_MAX_TOKENS=8192
```

### AWS Bedrock Embeddings & Reranking

No API key required — authentication is via the AWS credential chain (IAM role on ECS/EC2, or `~/.aws` / env vars locally).

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `BEDROCK_REGION` | `eu-central-1` | ❌ | AWS region for Bedrock API calls |
| `BEDROCK_CODE_EMBEDDING_MODEL` | `cohere.embed-multilingual-v3` | ❌ | Embedding model for code (→ `code_embeddings` table) |
| `BEDROCK_TEXT_EMBEDDING_MODEL` | `amazon.titan-embed-text-v2:0` | ❌ | Embedding model for knowledge/docs (→ `knowledge_embeddings` table) |
| `BEDROCK_RERANK_MODEL` | `amazon.rerank-v1:0` | ❌ | Rerank model for code semantic search |
| `EMBEDDING_MAX_SOURCE_CHARS` | `16000` | ❌ | Max source code chars per embedding |

Both models produce 1024-dimensional vectors — no database migration is required.

**Example:**
```bash
BEDROCK_REGION=eu-central-1
BEDROCK_CODE_EMBEDDING_MODEL=cohere.embed-multilingual-v3
BEDROCK_TEXT_EMBEDDING_MODEL=amazon.titan-embed-text-v2:0
BEDROCK_RERANK_MODEL=amazon.rerank-v1:0
```

**GDPR / data privacy:** AWS Bedrock does not use customer inputs to train models. Calls stay in the configured region. Apply an AI services opt-out policy in AWS Organizations to fully block any AWS AI service improvement usage.

## Database Configuration

### PostgreSQL Connection

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/code_agent` | ❌ | JDBC connection URL |
| `DATABASE_USER` | `code_agent` | ❌ | Database username |
| `DATABASE_PASSWORD` | - | ✅ | Database password |

**Example:**
```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/code_agent
DATABASE_USER=code_agent
DATABASE_PASSWORD=secure_password_here
```

**Production Example (AWS RDS):**
```bash
DATABASE_URL=jdbc:postgresql://mydb.cluster-xyz.us-west-2.rds.amazonaws.com:5432/code_agent
DATABASE_USER=code_agent
DATABASE_PASSWORD=complex_secure_password
```

## JIRA Cloud Integration

### Authentication & Connection

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `JIRA_BASE_URL` | - | ✅ | JIRA Cloud instance URL |
| `JIRA_USER` | - | ✅ | JIRA user email address |
| `JIRA_API_TOKEN` | - | ✅ | Atlassian API token |

### Workflow Transitions

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `JIRA_TRANSITION_IN_REVIEW` | - | ❌ | Transition ID for "In Review" |
| `JIRA_TRANSITION_DONE` | - | ❌ | Transition ID for "Done" |
| `JIRA_TRANSITION_REJECTED` | - | ❌ | Transition ID for rejected |
| `JIRA_DEFAULT_WORKLOG` | `30m` | ❌ | Default time logged per fix |

### Agent Configuration

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `JIRA_AGENT_ASSIGNEE` | - | ❌ | Agent user display name/email/account ID |
| `JIRA_AGENT_LABEL` | `WALL-E` | ❌ | Label used by `/sync-jira` |
| `JIRA_AGENT_DEFAULT_REPO_URL` | - | ❌ | Fallback repo URL when not resolvable |

**Example:**
```bash
JIRA_BASE_URL=https://mycompany.atlassian.net
JIRA_USER=code.agent@mycompany.com
JIRA_API_TOKEN=ATATT3xFFGF0UB1nqJn...
JIRA_AGENT_ASSIGNEE=Code Agent
JIRA_AGENT_LABEL=WALL-E
JIRA_DEFAULT_WORKLOG=45m
```

## Git Platform Configuration

### Platform Selection

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `GIT_PLATFORM` | `bitbucket` | ❌ | Platform: `bitbucket`, `azuredevops`, or `gitlab` |

### Git Operations

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `GIT_USERNAME` | `${BITBUCKET_USER}` | ❌ | Git clone/push username |
| `GIT_PASSWORD` | `${BITBUCKET_APP_PASSWORD}` | ❌ | Git clone/push password |
| `GIT_AUTHOR_NAME` | `code-agent` | ❌ | Git commit author name |
| `GIT_AUTHOR_EMAIL` | - | ❌ | Git commit author email |

### Bitbucket Cloud

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `BITBUCKET_BASE_URL` | `https://api.bitbucket.org/2.0` | ❌ | Bitbucket Cloud API base URL |
| `BITBUCKET_WORKSPACE` | - | ✅ | Bitbucket workspace slug |
| `BITBUCKET_USER` | - | ✅ | Bitbucket username |
| `BITBUCKET_APP_PASSWORD` | - | ✅ | Bitbucket App Password |

**Example:**
```bash
GIT_PLATFORM=bitbucket
BITBUCKET_WORKSPACE=mycompany
BITBUCKET_USER=code-agent-user
BITBUCKET_APP_PASSWORD=ATCTT3xFFGF0...
GIT_AUTHOR_EMAIL=code.agent@mycompany.com
```

### Azure DevOps

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `AZUREDEVOPS_BASE_URL` | `https://dev.azure.com` | ❌ | Azure DevOps base URL |
| `AZUREDEVOPS_PAT` | - | ✅ | Personal Access Token |
| `AZUREDEVOPS_AGENT_USER` | - | ❌ | Agent user display name |

**Example:**
```bash
GIT_PLATFORM=azuredevops
AZUREDEVOPS_PAT=abc123xyz...
AZUREDEVOPS_AGENT_USER=Code Agent
```

### GitLab

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `GITLAB_BASE_URL` | `https://gitlab.com/api/v4` | ❌ | GitLab API base URL |
| `GITLAB_TOKEN` | - | ✅ | GitLab access token |
| `GITLAB_AGENT_USER` | - | ❌ | Agent user display name |

**Example:**
```bash
GIT_PLATFORM=gitlab
GITLAB_BASE_URL=https://gitlab.mycompany.com/api/v4
GITLAB_TOKEN=glpat-xyz123...
GITLAB_AGENT_USER=Code Agent
```

## External Integrations

### Aikido Security

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `AIKIDO_BASE_URL` | `https://app.aikido.dev` | ❌ | Aikido API base URL |
| `AIKIDO_CLIENT_ID` | - | ❌ | Aikido OAuth2 client ID |
| `AIKIDO_CLIENT_SECRET` | - | ❌ | Aikido OAuth2 client secret |
| `AIKIDO_CI_API_SECRET` | - | ❌ | Aikido CI integration token |

**Example:**
```bash
AIKIDO_CLIENT_ID=aikido_client_xyz
AIKIDO_CLIENT_SECRET=AIK_SECRET_abc123
AIKIDO_CI_API_SECRET=AIK_CI_def456
```

### Notifications

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `TEAMS_WEBHOOK_URL` | - | ❌ | Microsoft Teams incoming webhook URL |
| `N8N_WEBHOOK_URL` | - | ❌ | Default n8n webhook URL for notifications |

**Example:**
```bash
TEAMS_WEBHOOK_URL=https://mycompany.webhook.office.com/webhookb2/abc123
N8N_WEBHOOK_URL=https://n8n.mycompany.com/webhook/code-agent
```

### Confluence Cloud

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `CONFLUENCE_BASE_URL` | - | ❌ | Confluence Cloud base URL |
| `CONFLUENCE_USER` | - | ❌ | Confluence user email |
| `CONFLUENCE_API_TOKEN` | - | ❌ | Atlassian API token for Confluence |

**Example:**
```bash
CONFLUENCE_BASE_URL=https://mycompany.atlassian.net/wiki
CONFLUENCE_USER=code.agent@mycompany.com
CONFLUENCE_API_TOKEN=ATATT3xFFGF0...
```

## Coding Rules & Standards

### Cursor Rules Integration

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `RULES_REPO_URL` | - | ❌ | Default shared Cursor rules repository |
| `RULES_REPO_CACHE_DIR` | `/tmp/cursor-rules-cache` | ❌ | Local cache for rules repository |
| `RULES_AUTO_READ_TARGET_REPO` | `true` | ❌ | Auto-load `.cursor/rules` from target repo |

**Example:**
```bash
RULES_REPO_URL=https://bitbucket.org/mycompany/cursor-rules.git
RULES_REPO_CACHE_DIR=/var/cache/cursor-rules
RULES_AUTO_READ_TARGET_REPO=true
```

## Security Configuration

### API Protection

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `API_KEY` | - | ❌ | Shared API key for REST endpoints |

**Example:**
```bash
API_KEY=secure-random-api-key-here
```

### Webhook Security

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `WEBHOOK_SECRET_BITBUCKET` | - | ❌ | HMAC-SHA256 secret for Bitbucket webhooks |
| `WEBHOOK_SECRET_AZUREDEVOPS` | - | ❌ | Secret for Azure DevOps webhook verification |
| `WEBHOOK_SECRET_GITLAB` | - | ❌ | Secret for GitLab webhook verification |
| `WEBHOOK_SECRET_JIRA` | - | ❌ | Secret for JIRA webhook verification |

**Example:**
```bash
WEBHOOK_SECRET_BITBUCKET=bitbucket-webhook-secret-123
WEBHOOK_SECRET_AZUREDEVOPS=ado-webhook-secret-456
WEBHOOK_SECRET_GITLAB=gitlab-webhook-secret-789
WEBHOOK_SECRET_JIRA=jira-webhook-secret-abc
```

### Guardrails

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `RUN_FIX_BLOCKED_PATHS` | `src/main/security,src/main/billing,.github,.env` | ❌ | Comma-separated blocked paths |
| `RUN_FIX_ALLOWED_COMMANDS` | `mvn,git diff,git status,git log,ls,find,cat,grep,dotnet,npm,npx` | ❌ | Comma-separated allowed command prefixes |
| `RUN_FIX_MAX_FILES_CHANGED` | `10` | ❌ | Maximum files the agent may change |
| `RUN_FIX_MAX_LINES_CHANGED` | `500` | ❌ | Maximum lines the agent may change |
| `RUN_FIX_MAX_LOOP_ITERATIONS` | `150` | ❌ | Maximum agent loop iterations |
| `RUN_FIX_JOB_TIMEOUT_MINUTES` | `30` | ❌ | Overall job timeout in minutes |

**Example:**
```bash
RUN_FIX_BLOCKED_PATHS=src/main/security,src/main/billing,src/main/payment,.env,.github
RUN_FIX_ALLOWED_COMMANDS=mvn,gradle,git diff,git status,ls,find,cat
RUN_FIX_MAX_FILES_CHANGED=5
RUN_FIX_MAX_LINES_CHANGED=250
```

## Job Processing Configuration

### Job Queue Settings

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `RUN_FIX_MAX_CONCURRENT_JOBS` | `3` | ❌ | Maximum jobs running in parallel |
| `RUN_FIX_MAX_QUEUE_SIZE` | `20` | ❌ | Maximum jobs waiting in queue |

### Job Type Specific Settings

#### Test Generation
| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `GENERATE_TESTS_MAX_LOOP_ITERATIONS` | `500` | ❌ | Max iterations for test generation |
| `GENERATE_TESTS_JOB_TIMEOUT_MINUTES` | `60` | ❌ | Test generation timeout |

#### Documentation Generation
| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `GENERATE_DOCS_MAX_LOOP_ITERATIONS` | `200` | ❌ | Max iterations for docs generation |

**Example:**
```bash
RUN_FIX_MAX_CONCURRENT_JOBS=5
RUN_FIX_MAX_QUEUE_SIZE=30
GENERATE_TESTS_JOB_TIMEOUT_MINUTES=90
```

## Code Review Configuration

### PR Review Behavior

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `REVIEW_WEBHOOK_SKIP_AUTHORS` | `code-agent` | ❌ | Comma-separated authors to skip |
| `REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD` | - | ❌ | Only review PRs with this keyword in title |
| `REVIEW_PR_SUMMARY_ENABLED` | `true` | ❌ | Generate PR summary & walkthrough |
| `REVIEW_SEQUENCE_DIAGRAMS_ENABLED` | `true` | ❌ | Include Mermaid diagrams in reviews |

### False Positive Handling

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `REVIEW_FP_AUTO_SUPPRESS_THRESHOLD` | `3` | ❌ | `/fp` marks needed for auto-suppression |

**Example:**
```bash
REVIEW_WEBHOOK_SKIP_AUTHORS=code-agent,automated-bot,dependabot
REVIEW_WEBHOOK_REQUIRE_TITLE_KEYWORD=READY
REVIEW_PR_SUMMARY_ENABLED=true
REVIEW_FP_AUTO_SUPPRESS_THRESHOLD=5
```

## Static Analysis Configuration

### Linter Integration

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `LINTER_ENABLED` | `true` | ❌ | Enable linter integration |
| `LINTER_CHECKSTYLE_ENABLED` | `true` | ❌ | Enable Checkstyle |
| `LINTER_PMD_ENABLED` | `true` | ❌ | Enable PMD |
| `LINTER_SPOTBUGS_ENABLED` | `true` | ❌ | Enable SpotBugs |
| `LINTER_ESLINT_ENABLED` | `true` | ❌ | Enable ESLint |
| `LINTER_DOTNET_FORMAT_ENABLED` | `true` | ❌ | Enable dotnet format |

### Linter Behavior

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `LINTER_MAX_FIX_ITERATIONS` | `2` | ❌ | Max iterations for auto-fixing linter issues |
| `LINTER_FAIL_ON_NEW_ISSUES` | `false` | ❌ | Fail build on new linter issues |
| `LINTER_TIMEOUT_MINUTES` | `10` | ❌ | Linter execution timeout |

**Example:**
```bash
LINTER_ENABLED=true
LINTER_CHECKSTYLE_ENABLED=true
LINTER_PMD_ENABLED=false
LINTER_MAX_FIX_ITERATIONS=3
LINTER_TIMEOUT_MINUTES=15
```

## Tools Configuration

### Documentation Lookup

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `TOOLS_FETCH_URL_ENABLED` | `true` | ❌ | Enable fetch_url tool |
| `TOOLS_FETCH_URL_TIMEOUT_SECONDS` | `15` | ❌ | HTTP request timeout |
| `TOOLS_FETCH_URL_ALLOWED_DOMAINS` | - | ❌ | Comma-separated allowed domains |

**Example:**
```bash
TOOLS_FETCH_URL_ENABLED=true
TOOLS_FETCH_URL_TIMEOUT_SECONDS=20
TOOLS_FETCH_URL_ALLOWED_DOMAINS=docs.spring.io,quarkus.io,developer.mozilla.org
```

## Code Graph Configuration

### Background Processing

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `CODE_GRAPH_SCHEDULER_ENABLED` | `true` | ❌ | Enable background graph pre-building |
| `CODE_GRAPH_SCHEDULER_DEFAULT_BRANCH` | `main` | ❌ | Default branch for graph builds |
| `CODE_GRAPH_SCHEDULER_CLONE_TIMEOUT` | `10` | ❌ | Clone timeout in minutes |

**Example:**
```bash
CODE_GRAPH_SCHEDULER_ENABLED=true
CODE_GRAPH_SCHEDULER_DEFAULT_BRANCH=develop
CODE_GRAPH_SCHEDULER_CLONE_TIMEOUT=15
```

## Maven/Nexus Configuration

### Private Repository Support

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `NEXUS_URL` | - | ❌ | Nexus repository URL |
| `NEXUS_USERNAME` | - | ❌ | Nexus username |
| `NEXUS_PASSWORD` | - | ❌ | Nexus password |

**Example:**
```bash
NEXUS_URL=https://nexus.mycompany.com/repository/maven-public/
NEXUS_USERNAME=code-agent
NEXUS_PASSWORD=nexus-secure-password
```

## Application Server Configuration

### Quarkus Settings

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `QUARKUS_HTTP_PORT` | `8080` | ❌ | HTTP server port |
| `QUARKUS_LOG_LEVEL` | `INFO` | ❌ | Global log level |
| `QUARKUS_LOG_CATEGORY_COM_ENEVE_AGENT_LEVEL` | `DEBUG` | ❌ | Application log level |

### Vertx Configuration

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `QUARKUS_VERTX_MAX_WORKER_EXECUTE_TIME` | `15m` | ❌ | Max worker thread execution time |

**Example:**
```bash
QUARKUS_HTTP_PORT=8080
QUARKUS_LOG_LEVEL=INFO
QUARKUS_LOG_CATEGORY_COM_ENEVE_AGENT_LEVEL=DEBUG
QUARKUS_VERTX_MAX_WORKER_EXECUTE_TIME=20m
```

## Environment-Specific Examples

### Development Environment

```bash
# Core
ANTHROPIC_API_KEY=sk-ant-api03-dev-key
DATABASE_PASSWORD=dev_password

# JIRA
JIRA_BASE_URL=https://mycompany-dev.atlassian.net
JIRA_USER=dev-agent@mycompany.com
JIRA_API_TOKEN=DEV_TOKEN_HERE

# Bitbucket
BITBUCKET_WORKSPACE=mycompany-dev
BITBUCKET_USER=dev-code-agent
BITBUCKET_APP_PASSWORD=DEV_APP_PASSWORD

# Relaxed limits for development
RUN_FIX_MAX_FILES_CHANGED=20
RUN_FIX_MAX_LINES_CHANGED=1000
LINTER_ENABLED=false
```

### Production Environment

```bash
# Core (use Secrets Manager)
ANTHROPIC_API_KEY=${SECRET:ANTHROPIC_API_KEY}
DATABASE_PASSWORD=${SECRET:DATABASE_PASSWORD}

# Security
API_KEY=${SECRET:API_KEY}
WEBHOOK_SECRET_BITBUCKET=${SECRET:WEBHOOK_SECRET_BITBUCKET}

# Production URLs
JIRA_BASE_URL=https://mycompany.atlassian.net
BITBUCKET_WORKSPACE=mycompany

# Production limits
RUN_FIX_MAX_CONCURRENT_JOBS=5
RUN_FIX_MAX_QUEUE_SIZE=50
RUN_FIX_MAX_FILES_CHANGED=10
RUN_FIX_MAX_LINES_CHANGED=500
RUN_FIX_JOB_TIMEOUT_MINUTES=45

# Monitoring
QUARKUS_LOG_LEVEL=WARN
QUARKUS_LOG_CATEGORY_COM_ENEVE_AGENT_LEVEL=INFO
```

## Configuration Validation

### Required Configurations

The following configurations are required for basic operation:

1. **ANTHROPIC_API_KEY**: Claude API access
2. **DATABASE_PASSWORD**: PostgreSQL connection
3. **JIRA_BASE_URL, JIRA_USER, JIRA_API_TOKEN**: JIRA integration
4. **Git Platform Credentials**: Platform-specific authentication

### Optional but Recommended

1. **API_KEY**: Protect REST endpoints in production
2. **WEBHOOK_SECRET_***: Secure webhook endpoints
3. **AWS Bedrock access**: Ensure the ECS task role (or local IAM credentials) has `bedrock:InvokeModel` and `bedrock:Rerank` permissions to enable semantic search
4. **Guardrails**: Set appropriate limits for your environment

### Validation Checklist

- [ ] All required environment variables are set
- [ ] Database connection can be established
- [ ] API keys are valid and have necessary permissions
- [ ] Webhook secrets match configured values in external systems
- [ ] Guardrail limits are appropriate for your use case
- [ ] Log levels are suitable for your environment

## Security Best Practices

1. **Use Secrets Management**: Store sensitive values in AWS Secrets Manager, Azure Key Vault, or similar
2. **Rotate Credentials**: Regularly rotate API keys and tokens
3. **Least Privilege**: Grant minimum necessary permissions to service accounts
4. **Network Security**: Deploy in private subnets with proper security groups
5. **Audit Logging**: Enable appropriate log levels for security monitoring
6. **Webhook Verification**: Always set webhook secrets in production
7. **API Protection**: Use API keys to protect REST endpoints

This configuration reference provides all the details needed to properly configure the Code Agent Runner for your environment. Always validate configurations in a development environment before deploying to production.